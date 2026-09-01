# PaddleOCR HTTP Service for Aegis Platform
#
# 基于 python:3.10-slim 构建，pip install paddlepaddle + paddleocr + FastAPI。
# CPU-only 模式，适合 OCR 文字识别（不依赖 GPU）。
#
# 首次启动会自动下载 PP-OCRv5 模型权重（~10MB），之后缓存到 /root/.paddleocr。
#
# 使用: docker run -p 8000:8000 aegis-paddleocr:latest
#
# @author wang.zhen (Aegis Platform)

FROM python:3.10-slim

WORKDIR /app

# 系统依赖（PaddleOCR 的 OpenCV + PaddlePaddle 的 libgomp）
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libxrender1 \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# pip 镜像源可配置（默认官方源；国内可用 --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple）
ARG PIP_INDEX_URL=https://pypi.org/simple
# 先升级 pip
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} --upgrade pip

# 安装 PaddlePaddle CPU + PaddleOCR + FastAPI 包装层
# paddlepaddle CPU 版（x86_64）+ paddleocr（含 PP-OCRv5）
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} \
    paddlepaddle>=2.6.2 \
    paddleocr>=2.8.0 \
    fastapi>=0.110.0 \
    "uvicorn[standard]>=0.29.0" \
    Pillow>=9.0.0 \
    numpy>=1.22.0 \
    pydantic>=2.0.0

# 复制 FastAPI 入口
COPY app.py /app/app.py

# 暴露端口
EXPOSE 8000

# 健康检查（容器启动后 OCR 模型加载需要几秒，故 start-period=30s）
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=10 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health')" || exit 1

# 启动命令
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
