import base64
import json
import os
import tempfile
import uuid

from PIL import Image
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

# 声明一下工具: 编辑图片与合并图片
skills_tools = [edit_image_tool]

# 创建agent
try:
    deep_agent = create_deep_agent(
        model=llm,
        tools=skills_tools,
        skills=["/skills/"],
        system_prompt=(
            "你是一个智能图片处理助手，你可以调用一个工具：\n"
            "- edit_image_tool：编辑单张图片 \n"
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

        data = request.files["file"].read()
        p = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}.bin")
        with open(p, "wb") as f:
            f.write(data)
        tmp_paths.append(p)
        prompt_lines.append(f"image_path: {p}")
        out = invoke_agent("\n".join(prompt_lines))
        return out["url"]
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


