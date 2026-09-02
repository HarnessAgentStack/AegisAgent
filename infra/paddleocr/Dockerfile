# PaddleOCR HTTP Service for Aegis Platform
#
# 基于 python:3.10-slim 构建，pip install paddlepaddle + paddleocr + FastAPI。
# CPU-only 模式，适合 OCR 文字识别（不依赖 GPU）。
#
# 首次启动会自动下载 PP-OCRv5 模型权重（~10MB），之后缓存到 /root/.paddleocr。
#
# 使用: docker run -p 8000:8000 aegis-paddleocr:latest
# 构建: docker build --network host -f Dockerfile -t aegis-paddleocr:latest .
#
# @author wang.zhen (Aegis Platform)

FROM python:3.10-slim

WORKDIR /app

# apt 换清华镜像（debian bookworm/trixie 用 .sources 格式）
# 默认容器网络在部分 Windows+WSL2 环境下限速严重，构建时请加 --network host
RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null \
    || sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list 2>/dev/null; \
    apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libxrender1 \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# pip 换清华镜像（容器内默认网络限速时 --network host 同样生效）
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn

RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} --trusted-host ${PIP_TRUSTED_HOST} --upgrade pip

RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} --trusted-host ${PIP_TRUSTED_HOST} \
    paddlepaddle>=2.6.2 \
    paddleocr>=2.8.0 \
    fastapi>=0.110.0 \
    "uvicorn[standard]>=0.29.0" \
    Pillow>=9.0.0 \
    numpy>=1.22.0 \
    pydantic>=2.0.0

COPY app.py /app/app.py

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=10 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health')" || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
