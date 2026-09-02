# PaddleOCR HTTP Service for Aegis Platform
#
# 基于 python:3.10-slim 构建，pip install paddlepaddle + paddleocr + FastAPI。
# CPU-only 模式，适合 OCR 文字识别（不依赖 GPU）。
#
# 稳定组合（2026-09-02 验证通过）：
#   paddlepaddle==2.6.2  +  paddleocr==2.8.1
#
# WARNING: 不要用 >= 范围安装！paddlepaddle 3.x + paddleocr 3.x 在某些平台
#          （尤其是 Windows Docker Desktop + WSL2）有 oneDNN / PIR 算子兼容 bug
#          （ConvertPirAttribute2RuntimeAttribute not support），导致推理直接崩溃。
#          钉死 2.6.2 + 2.8.1 是经过生产验证的稳定组合。
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

# pip 换清华镜像
ENV PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ENV PIP_TRUSTED_HOST=pypi.tuna.tsinghua.edu.cn

RUN pip install --no-cache-dir --upgrade pip

# ============ 版本必须钉死，不能用 >= ============
# paddlepaddle 2.6.2 + paddleocr 2.8.1 是 CPU-only 稳定组合
RUN pip install --no-cache-dir \
    paddlepaddle==2.6.2 \
    paddleocr==2.8.1 \
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
