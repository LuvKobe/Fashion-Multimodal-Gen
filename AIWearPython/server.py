import base64
import json
import os
import tempfile
import uuid
import threading
import redis
import requests

from PIL import Image
import torch
from transformers import CLIPModel, CLIPProcessor
from dashscope import MultiModalConversation
from deepagents import create_deep_agent
from dotenv import load_dotenv
from flask import Flask, request, jsonify
from io import BytesIO

from langchain_community.chat_models import ChatTongyi
from langchain_core.messages import HumanMessage
from langchain_core.messages.tool import tool_call
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.tools import tool

# 创建Flask应用实例
app = Flask(__name__)

# 获取配置信息
load_dotenv()

API_KEY = os.getenv("DASHSCOPE_API_KEY")

# Redis 配置（按你给的配置）
REDIS_HOST = "8.156.77.78"
REDIS_PORT = 6379
REDIS_DB = 0
REDIS_TIMEOUT_SECS = 2

_redis_client = redis.Redis(
    host=REDIS_HOST,
    port=REDIS_PORT,
    db=REDIS_DB,
    socket_timeout=REDIS_TIMEOUT_SECS,
    socket_connect_timeout=REDIS_TIMEOUT_SECS,
    decode_responses=True,
)

# 模型存储的位置
CLIP_MODEL_DIR = r"C:\Users\20399\.cache\modelscope\hub\models\openai-mirror\clip-vit-base-patch16"
_clip_model = None
_clip_processor = None
_clip_lock = threading.Lock()

def get_clip_model_and_processor():
    """
    延迟加载本地 CLIP 模型，避免每次请求重复加载。
    该模型的 image embedding 维度通常为 512。
    """
    global _clip_model, _clip_processor
    if _clip_model is not None and _clip_processor is not None:
        return _clip_model, _clip_processor

    # 简单单例锁，确保并发下只加载一次
    with _clip_lock:
        if _clip_model is None or _clip_processor is None:
            _clip_processor = CLIPProcessor.from_pretrained(CLIP_MODEL_DIR)
            _clip_model = CLIPModel.from_pretrained(CLIP_MODEL_DIR)
            _clip_model.eval()

    return _clip_model, _clip_processor

# 将图片bytes转换成base_uri
def process_image(image_data : bytes) -> str:
    img = Image.open(BytesIO(image_data))
    image_format = (img.format).lower()
    image_base64 = base64.b64encode(image_data).decode("utf-8")
    data_uri = f"data:{image_format};base64,{image_base64}"
    return data_uri

# 调用大模型生成图片文字描述信息
def describe_image(image_data : bytes) -> str:
    try:
        # 1. 先把图片转化成base64
        data_uri = process_image(image_data)

        # 2. 构建LangChain的请求
        human_content = [
            {"image": data_uri},
            {
                "text": (
                    "用一句话简要地概括这张图片的内容，"
                    "并给出3到5个关键词（使用逗号分隔开），不要过多地解释"
                )
            },
        ]

        # 3. 构建访问大模型的实例
        vl_llm = ChatTongyi(
            model_name="qwen-vl-max",
            temperature=0.0,  # 视觉描述任务是不需要发散的
            dashscope_api_key=API_KEY  # 阿里百炼的Key
        )

        # 4. 把访问大模型得到的结果进行处理返回
        resp = vl_llm.invoke(
            [
                HumanMessage(content=human_content)
            ]
        )
        print(resp.content[0]['text'])
        return resp.content[0]['text']  # 返回转换好的文本内容
    except Exception as e:
        print(f"生成图片的文字描述信息出现异常:{e}")
        return ""

# 对图片的描述信息做最终的判定
def validate_image(image_desc : str) -> bool:
    try:
        # 1. 构建系统提示词和用户提示词
        prompt = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    "你是一个图片审核助手，当前的业务只允许两种图片："
                    "1) 衣服/服装/穿搭相关;"
                    "2) 人物人像(人脸照、半身照、全身照).\n"
                    "需要你来判断当前图片的内容是否是以上两类图片，如果是输出是，如果否输出否。"
                    "请严格只输出是或者否，不要别的内容"
                ),
                (
                    "human", f"图片文字描述: {image_desc}"
                )
            ]
        )

        # 2. 定义大模型
        llm = ChatTongyi(
            model_name="qwen-plus",
            temperature=0.7,
            dashscope_api_key=API_KEY
        )

        # 3. 把访问大模型得到的结果进行处理返回
        resp = llm.invoke(prompt.format_messages())
        text = resp.content
        print(text)
        return text.startswith("是")
    except Exception as e:
        print(f"做图片内容判定的时候出现异常:{e}")
        return False

def clip_image_to_512d(image_data: bytes) -> list:
    """
    把上传图片转成 CLIP 512 维向量（float 列表）。

    归一化后的向量更适合做余弦相似度检索。
    """
    model, processor = get_clip_model_and_processor()

    img = Image.open(BytesIO(image_data)).convert("RGB")
    inputs = processor(images=img, return_tensors="pt")

    with torch.no_grad():
        # 当前 transformers 版本下，get_image_features 返回 BaseModelOutputWithPooling
        # 其中向量通常在 pooler_output 字段里。
        out = model.get_image_features(**inputs)
        if hasattr(out, "pooler_output") and out.pooler_output is not None:
            image_features = out.pooler_output
        elif hasattr(out, "image_embeds") and out.image_embeds is not None:
            image_features = out.image_embeds
        else:
            image_features = out
        image_features = image_features / image_features.norm(dim=-1, keepdim=True)

    vec = image_features[0].detach().cpu().tolist()
    if len(vec) != 512:
        raise ValueError(f"CLIP embedding 维度期望为 512，实际为 {len(vec)}")

    # 降低 Redis 里 JSON 体积，检索时精度通常足够
    vec = [round(float(x), 6) for x in vec]
    return vec

# 定义审核图片的接口路由
@app.route("/api/validate-image", methods = ['POST'])
def validate_image_api():
    try:
        file = request.files["file"]
        image_data = file.read()
        desc = describe_image(image_data) # 调用大模型生成图片文字描述信息
        allow = validate_image(desc) # 二次调用大模型，处理文字描述信息判断当前图片是否符合要求
        return jsonify({"code":200, "allow":allow}), 200
    except Exception as e:
        print(f"执行审核图片操作捕获异常:{e}")
        return jsonify({"code":500, "allow":False}), 500

def _get_field_from_request(name: str, default=None):
    """兼容 JSON body 与 form body 的字段读取。"""
    # JSON body（不强依赖 Content-Type，避免前端没设置导致读取失败）
    body = request.get_json(silent=True) or {}
    if name in body:
        return body.get(name, default)

    # form-data / x-www-form-urlencoded
    if name in request.form:
        return request.form.get(name, default)

    # query string
    if name in request.args:
        return request.args.get(name, default)

    return default

# 获取ID
def _extract_user_id():
    user_id = _get_field_from_request("userId")
    if user_id is None:
        user_id = _get_field_from_request("user_id")
    if user_id is None:
        raise ValueError("userId 不能为空")

    # 允许前端传字符串数字
    try:
        return int(user_id)
    except Exception:
        return str(user_id)

# 获取url
def _extract_oss_url():
    for key in [
        "ossUrl",
        "oss_url",
        "oss_address",
        "ossAddress",
        "imageOssUrl",
        "image_oss_url",
    ]:
        v = _get_field_from_request(key)
        if v:
            return v

    # 兜底：有些前端把字段叫 url
    v = _get_field_from_request("url")
    return v

@app.route("/api/upload-image", methods=["POST"])
def upload_image_api():
    """
    接收参数:
    - ossUrl / oss_address: 图片 OSS 地址（已可访问的 http(s) URL）
    - userId: 用户 ID

    处理流程:
    1) 下载 OSS 图片 -> bytes
    2) 调用 qwen-vl-max 生成图片描述
    3) 调用本地 CLIP 生成 512 维向量
    4) 把 {userId, description, embedding} 写入 Redis（JSON 格式）
    """
    try:
        user_id = _extract_user_id()
        oss_url = _extract_oss_url()
        if not oss_url:
            return jsonify({"success": False, "error": "ossUrl/oss_address 不能为空"}), 400

        # 下载图片（OSS 通常是可直接 GET 的公开/内网 URL）
        resp = requests.get(oss_url, timeout=20)
        if resp.status_code != 200:
            return (
                jsonify(
                    {
                        "success": False,
                        "error": f"下载图片失败，status={resp.status_code}",
                    }
                ),
                400,
            )

        image_data = resp.content
        if not image_data:
            return jsonify({"success": False, "error": "下载到的图片为空"}), 400

        # 1) qwen-vl-max: 描述
        description = describe_image(image_data)
        if not description:
            return jsonify({"success": False, "error": "生成图片描述失败"}), 500

        # 2) CLIP: 向量（512维）
        embedding = clip_image_to_512d(image_data)

        # 3) 写入 Redis
        image_id = uuid.uuid4().hex
        payload = {
            "userId": user_id,
            "imageId": image_id,
            "ossUrl": oss_url,
            "description": description,
            "embedding512": embedding,
        }
        redis_key = f"clip:image:{image_id}"
        _redis_client.set(redis_key, json.dumps(payload, ensure_ascii=False))
        # 同时维护 user 的索引集合，后续你按 userId 扫描会更快（避免 keys * 全库扫描）
        _redis_client.sadd(f"clip:user:{user_id}:image_ids", image_id)

        return (
            jsonify(
                {
                    "success": True,
                    "imageId": image_id,
                    "description": description,
                    "embeddingDim": 512,
                }
            ),
            200,
        )
    except Exception as e:
        print(f"/api/upload-image 执行失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500

# 全局的agent智能体
deep_agent = None

# 声明大模型
llm = ChatTongyi(
    model_name="qwen-plus",
    temperature=0.1, # 随机性
    dashscope_api_key = API_KEY
)

@tool
def edit_image_tool(image_path: str, instruction: str) -> str:
    """编辑单张图片"
    输入本地图片地址路径与编辑指令   调用Dashscope的图像编辑模型生成新的URL
    返回JSON字符串: {"success": true, "url":"..."} 或 {"success": false, "error":"..."}
    """
    try:
        with open(image_path, "rb") as f:
            image_data = f.read()
        image_data_uri = process_image(image_data)

        messages = [
            {
                "role": "user",
                "content": [
                    {
                        "image": image_data_uri
                    },
                    {
                        "text": instruction
                    }
                ]
            }
        ]

        # 构建请求大模型的参数
        params = {
            "model": "qwen-image-edit-plus",
            "messages": messages
        }

        # 调用Dashscope编辑模型
        response = MultiModalConversation.call(**params)

        url = response['output']['choices'][0]['message']['content'][0]['image']
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        print(f"调用编辑模型报错:{e}")
        return json.dumps({"success": False, "error": f"{e}"}, ensure_ascii=False)

@tool
def merge_image_tool(image_path1: str, image_path2: str, instruction: str) -> str:
    """合并两张图片"
    输入两张本地图片地址路径与合并指令，调用Dashscope的图像编辑模型生成新的URL
    返回JSON字符串: {"success": true, "url":"..."} 或 {"success": false, "error":"..."}
    """
    try:
        with open(image_path1, "rb") as f:
            image_data1 = f.read()
        with open(image_path2, "rb") as f:
            image_data2 = f.read()

        image_data_uri1 = process_image(image_data1)
        image_data_uri2 = process_image(image_data2)

        # 多图输入：把两张图都放进同一个 user content 里
        messages = [
            {
                "role": "user",
                "content": [
                    {"image": image_data_uri1},
                    {"image": image_data_uri2},
                    {"text": instruction},
                ],
            }
        ]

        params = {
            "model": "qwen-image-edit-plus",
            "messages": messages,
        }

        response = MultiModalConversation.call(**params)
        url = response["output"]["choices"][0]["message"]["content"][0]["image"]
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        print(f"调用合并模型报错:{e}")
        return json.dumps({"success": False, "error": f"{e}"}, ensure_ascii=False)

# 声明一下工具: 编辑图片与合并图片
skills_tools = [edit_image_tool, merge_image_tool]

# 创建agent
try:
    deep_agent = create_deep_agent(
        model=llm,
        tools=skills_tools,
        skills=["/skills/"],
        system_prompt=(
            "你是一个智能图片处理助手，你可以调用一个工具：\n"
            "- edit_image_tool：编辑单张图片 \n"
            "- merge_image_tool：合并两张图片 \n"
            "最终的输出格式JSON: {\"success\":true, \"url\":\"字符串类型\"} 或者 {\"success\":false, \"error\":\"字符串类型\"} "
        )
    )
    print("Agent 已经启用")
except Exception as e:
    print("Agent 未启用")
    deep_agent = None

# agent执行函数
def invoke_agent(param : str) -> dict:
    try:
        state = deep_agent.invoke(
            {
                "messages": [
                    {
                        "role": "user",
                        "content": param
                    }
                ]
            }
        )
        msg = (state or {}).get("messages") or []
        last = msg[-1] if msg else None
        content = last.content
        obj = json.loads(content)
        return obj
    except Exception as e:
        print(f"执行agent函数出现异常{e}")
        return {"success": False, "error": f"执行agent函数失败{e}"}

# 操作图片的函数
def skill_image() -> str:
    try:
        if deep_agent is None:
            return ""
        instruction = request.form.get("instruction")
        if not instruction:
            return ""

        tmp_dir = tempfile.gettempdir()
        tmp_paths = []

        prompt_lines = [
            "你必须调用一个工具，并且只输出 JSON格式",
            f"instruction: {instruction}"
        ]

        # data = request.files["file"].read()
        # p = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}.bin")
        # with open(p, "wb") as f:
        #     f.write(data)
        # tmp_paths.append(p)
        # prompt_lines.append(f"image_path: {p}")
        # out = invoke_agent("\n".join(prompt_lines))
        # return out["url"]

        # 兼容旧接口：单图编辑仍然使用 file 字段
        # 新增能力：如果传 file1 + file2，则走合并路径
        if "file1" in request.files and "file2" in request.files:
            data1 = request.files["file1"].read()
            data2 = request.files["file2"].read()
            p1 = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}_1.bin")
            p2 = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}_2.bin")
            with open(p1, "wb") as f:
                f.write(data1)
            with open(p2, "wb") as f:
                f.write(data2)
            tmp_paths.extend([p1, p2])
            prompt_lines.append(f"image_path1: {p1}")
            prompt_lines.append(f"image_path2: {p2}")
        else:
            data = request.files["file"].read()
            p = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}.bin")
            with open(p, "wb") as f:
                f.write(data)
            tmp_paths.append(p)
            prompt_lines.append(f"image_path: {p}")
        out = invoke_agent("\n".join(prompt_lines))
        return out.get("url", "")
    except Exception as e:
        print(f"执行skill异常{e}")
        return ""

# 定义操作图片（编辑图片+合并图片）的接口路由
@app.route("/api/skill/image", methods = ['POST'])
def skill_image_api():
    try:
        out = skill_image()
        return jsonify(
            {
                "success": True,
                "url": out,
            }
        ), 200
    except Exception as e:
        print(f"调用skill处理失败{e}")
        return jsonify(
            {
                "success": False,
                "error": str(e)
            }
        ), 500

# 服务启动函数
if __name__ == "__main__":
    print("AI服务启动成功！")
    app.run(debug=True, host="0.0.0.0", port=5000)


