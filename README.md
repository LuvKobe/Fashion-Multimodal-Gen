# Fashion-Multimodal-Gen


### 基于LangChain的多模态服装生成与编辑平台

*LangChain-based Multimodal Fashion Generation & Editing Platform*


## 🛠️ 环境要求 (Environment)

在开始部署之前，请确保您的服务器满足以下环境要求：

* **操作系统**: Ubuntu 22.04
* **Java**: OpenJDK 17+ (推荐 17.0.18)
* **Python**: Python 3.13.x
* **Docker**: 已安装 Docker 及 Docker Compose
> 💡 Docker 安装可参考：[【Docker】Ubuntu22.04 安装 Docker 教程](https://techflow.blog.csdn.net/article/details/160390775)



## 🚀 部署指南 (Deployment Guide)

整个项目由 **中间件**、**Java 后端** 和 **Python 算法服务** 三部分组成，请按照以下步骤依次部署。

### 1. 中间件部署 (Docker Compose)

（1）在服务器上创建目标目录并赋予最高权限：
```bash
mkdir -p /root/AIWear
chmod -R 777 /root/AIWear/
```


（2）将中间件压缩包 `deploy.tar.gz` 上传并解压到 `/root/AIWear/` 目录下。

（3）进入解压后的 `deploy` 目录，使用 Docker Compose 启动中间件：
```bash
cd /root/AIWear/deploy
docker compose -p edison_wear -f docker-compose-mid.yml up -d
```

> 📌 注意：前端静态资源已内置于 `/AIWear/deploy/nginx/web/` 目录中，随着中间件的启动，Nginx 会自动完成前端托管。


### 2. Java 后端服务部署

（1）创建 Java 服务工作目录，并将 `AIWear-1.0-SNAPSHOT.jar` 拷贝至该目录：
```bash
mkdir -p /root/AIJava
cd /root/AIJava
```


（2）使用 `nohup` 在后台启动 Java 服务：
```bash
nohup java -jar AIWear-1.0-SNAPSHOT.jar > AIWear.log 2>&1 &
```


（3）运维命令：
* 查看运行日志：`tail -f AIWear.log`
* 查看 Java 进程：`ps -ef | grep java`

### 3. Python 算法服务部署

（1）将项目源码 `AIWearPython.tar.gz` 和本地模型 `clip-vit-base-patch16.tar.gz` 上传并解压至 `/root/` 目录下。

（2）进入 Python 项目根目录：
```bash
cd /root/AIWearPython
```


（3）创建并激活 `Python 3.13` 虚拟环境：
```bash
python3.13 -m venv venv313
source venv313/bin/activate
```

（4）在虚拟环境下使用阿里云镜像源安装依赖：
```bash
pip install -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/
```


（5）以守护进程（后台挂起）的方式启动 Python 服务：
```bash
nohup /root/AIWearPython/venv313/bin/python3 /root/AIWearPython/server.py > python.log 2>&1 &
```


（6）运维命令：
* 查看运行日志：`tail -f python.log`
* 查看 Python 进程：`ps -ef | grep python`


## 🌐 访问项目

当以上服务全部启动成功后，打开浏览器访问以下地址即可体验平台：

```text
http://<你的服务器IP地址>
```