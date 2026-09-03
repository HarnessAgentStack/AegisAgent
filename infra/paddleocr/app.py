# -*- coding: utf-8 -*-
"""
PaddleOCR HTTP Service for Aegis Platform.

提供 /ocr 端点，接受 Base64 编码的图片，返回结构化 OCR 结果。
基于 PaddleOCR 2.8.1 + PP-OCRv5（中文/英文数字高精度识别）。

启动：uvicorn app:app --host 0.0.0.0 --port 8000

API 版本说明：
  paddlepaddle==2.6.2 + paddleocr==2.8.1 是稳定组合。
  - PaddleOCR(use_angle_cls=True, lang='ch')  ✅ 有效
  - ocr.ocr(img_bytes, cls=True)               ✅ cls 参数有效

@author wang.zhen (Aegis Platform)
"""
import base64
import time
from typing import List, Optional

# P0-2 修复：在导入 paddleocr 前关闭 MKLDNN/IR 算子融合，规避 SelfAttentionFusePass
# 在不支持 AVX2/FMA 的宿主 CPU 上触发的 SIGILL（Illegal instruction）死循环。
# 必须在 import paddleocr / paddle 之前设置 flags 才能生效。
import paddle
paddle.set_flags({'FLAGS_use_mkldnn': False,
                  'FLAGS_enable_self_attention_fuse': False,
                  'FLAGS_enable_ir_optimization': False})

import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from paddleocr import PaddleOCR

app = FastAPI(title="PaddleOCR Service", version="1.0.0")

# 全局 OCR 实例（CPU 模式）
# use_angle_cls=True: 启用方向分类（0/90/180/270），提升旋转文字识别率
# lang='ch': 中文（含英文数字符号），如需纯英文改 lang='en'
# use_gpu=False: 强制 CPU 模式；enable_mkldnn=False: 与 flags 一致关闭 MKLDNN
ocr = PaddleOCR(use_angle_cls=True, lang='ch', show_log=False,
                use_gpu=False, enable_mkldnn=False)


class OcrRequest(BaseModel):
    """OCR 请求体"""
    image_base64: str = Field(..., description="Base64 编码的图片（不含 data:image/xxx;base64, 前缀）")
    detect_table: bool = Field(default=False, description="是否启用表格结构识别（额外耗时）")


class TextLine(BaseModel):
    """单行识别结果"""
    text: str
    confidence: float
    bbox: List[List[int]] = Field(description="四坐标 [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]")


class OcrResponse(BaseModel):
    """OCR 响应体"""
    success: bool
    text_lines: List[TextLine] = Field(default_factory=list, description="逐行识别结果")
    full_text: str = Field(default="", description="全量识别文本（换行拼接）")
    elapsed_ms: int = Field(description="耗时毫秒")
    table_markdown: Optional[str] = Field(default=None, description="表格识别的 Markdown（仅 detect_table=true 时返回）")


@app.get("/health")
def health():
    """健康检查"""
    return {"status": "ok", "service": "paddleocr", "version": "1.0.0"}


@app.post("/ocr", response_model=OcrResponse)
def do_ocr(req: OcrRequest):
    """
    执行 OCR 识别。

    请求体: image_base64 (str), detect_table (bool, optional)
    响应:   OcrResponse (success, text_lines, full_text, elapsed_ms, table_markdown)
    """
    start = time.time()

    # 1. Base64 解码为 numpy 数组（PaddleOCR 接受 np.ndarray 或 PIL.Image）
    try:
        img_bytes = base64.b64decode(req.image_base64)
        # 转成 numpy (PIL 读一下 → np.array，兼容 jpg/png/webp 所有格式)
        from PIL import Image
        import io as _io
        pil_img = Image.open(_io.BytesIO(img_bytes))
        img_array = np.array(pil_img)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"图片解码失败: {e}")

    # 2. PaddleOCR 推理（2.8.1 API: cls=True 表示在推理阶段做方向分类）
    try:
        result = ocr.ocr(img_array, cls=True)
    except Exception as e:
        elapsed = int((time.time() - start) * 1000)
        return JSONResponse(status_code=500, content={
            "success": False,
            "text_lines": [],
            "full_text": f"OCR 推理失败: {e}",
            "elapsed_ms": elapsed,
        })

    # 3. 解析结果
    # 2.8.1 返回格式: [[ [box, (text, conf)], [box, (text, conf)], ... ]]
    text_lines: List[TextLine] = []
    full_text_parts: List[str] = []

    if result and len(result) > 0 and result[0]:
        for line in result[0]:
            try:
                box, (text, conf) = line
                bbox = [[int(p[0]), int(p[1])] for p in box]
                text_lines.append(TextLine(text=str(text), confidence=float(conf), bbox=bbox))
                full_text_parts.append(str(text))
            except Exception:
                # 跳过格式异常的行
                continue

    elapsed = int((time.time() - start) * 1000)

    return OcrResponse(
        success=True,
        text_lines=text_lines,
        full_text="\n".join(full_text_parts),
        elapsed_ms=elapsed,
        table_markdown=None,
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
