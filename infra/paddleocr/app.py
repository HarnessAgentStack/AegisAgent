"""
PaddleOCR HTTP Service for Aegis Platform.

提供 /ocr 端点，接受 Base64 编码的图片，返回结构化 OCR 结果。
基于 PaddleOCR PP-OCRv5（中文/英文数字高精度识别）。

启动：uvicorn app:app --host 0.0.0.0 --port 8000

@author wang.zhen (Aegis Platform)
"""
import base64
import io
import time
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from paddleocr import PaddleOCR

app = FastAPI(title="PaddleOCR Service", version="1.0.0")

# 全局 OCR 实例（CPU 模式）
# use_angle_cls=True: 启用方向分类（0/90/180/270），提升旋转文字识别率
# lang='ch': 中文（含英文数字符号），如需纯英文改 lang='en'
ocr = PaddleOCR(use_angle_cls=True, lang='ch')


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

    # 1. Base64 解码为 PIL Image
    try:
        img_bytes = base64.b64decode(req.image_base64)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Base64 解码失败: {e}")

    # 2. PaddleOCR 推理
    # ocr.ocr() 返回 [[ [box, (text, conf)], ... ]] 格式
    try:
        result = ocr.ocr(img_bytes, cls=True)
    except Exception as e:
        elapsed = int((time.time() - start) * 1000)
        return JSONResponse(status_code=500, content={
            "success": False,
            "text_lines": [],
            "full_text": f"OCR 推理失败: {e}",
            "elapsed_ms": elapsed,
        })

    # 3. 解析结果
    text_lines: List[TextLine] = []
    full_text_parts: List[str] = []

    if result and result[0]:
        for line in result[0]:
            box, (text, conf) = line
            # box 格式 [[x1,y1],[x2,y2],[x3,y3],[x4,y4]] → 转成 int
            bbox = [[int(p[0]), int(p[1])] for p in box]
            text_lines.append(TextLine(text=text, confidence=float(conf), bbox=bbox))
            full_text_parts.append(text)

    elapsed = int((time.time() - start) * 1000)

    return OcrResponse(
        success=True,
        text_lines=text_lines,
        full_text="\n".join(full_text_parts),
        elapsed_ms=elapsed,
        table_markdown=None,  # 暂不做表格结构化（检测耗时且需额外依赖）
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
