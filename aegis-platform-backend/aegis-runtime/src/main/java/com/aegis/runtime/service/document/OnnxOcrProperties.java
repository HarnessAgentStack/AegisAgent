package com.aegis.runtime.service.document;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ONNX Runtime Java 嵌入 OCR 配置。
 *
 * <p>通过 {@code aegis.ocr.onnx} 前缀绑定 application.yml。
 * 所有字段都有安全默认值。</p>
 *
 * <h3>模型文件来源</h3>
 * <p>det.onnx + rec.onnx + char_dict.txt 三文件放在 {@code models/onnx/} 目录下，
 * 启动脚本（download-ocr-models.ps1）自动从 PaddleOCR-ONNX 社区仓库下载。</p>
 *
 * @author wang.zhen
 */
@Data
@ConfigurationProperties(prefix = "aegis.ocr.onnx")
public class OnnxOcrProperties {

    /** 是否启用 ONNX OCR（默认 true，启用 ONNX 嵌入推理） */
    private boolean enabled = true;

    /** det 模型 ONNX 文件路径（DB 文字区域分割，默认 classpath 外部路径） */
    private String detModelPath = "./models/ocr/det.onnx";

    /** rec 模型 ONNX 文件路径（CRNN 序列文字识别） */
    private String recModelPath = "./models/ocr/rec.onnx";

    /** 中文字符典文件路径（6623 字 PaddleOCR dict，每行一个字符） */
    private String charDictPath = "./models/ocr/ppocr_keys_v1.txt";

    /** det 输入尺寸（默认 960×960） */
    private int detInputWidth = 960;
    private int detInputHeight = 960;

    /** det 后处理阈值（DB 二值化 threshold，默认 0.3） */
    private float detThreshold = 0.3f;

    /** det box 最小面积（过滤碎片，默认 3.0） */
    private float detMinArea = 3.0f;

    /** rec 输入尺寸（默认 48×320，H×W） */
    private int recInputHeight = 48;
    private int recInputWidth = 320;

    /** 单次图片最大字节数（默认 10MB） */
    private int maxImageBytes = 10 * 1024 * 1024;

    /** OCR 返回 fullText 字符数阈值（≥ 此值视为 OCR 有效） */
    private int minTextLengthThreshold = 3;

    /** ONNX Runtime 推理线程数（0=自动取 CPU 核数-1） */
    private int numThreads = 0;

    /** 是否启用 graph optimization（速度优化，默认 ON） */
    private boolean graphOptimize = true;
}
