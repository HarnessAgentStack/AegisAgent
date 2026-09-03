package com.aegis.runtime.service.document;

import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ONNX Runtime Java 嵌入 OCR 客户端。
 *
 * <p>使用 PaddleOCR 官方 det.onnx（DB 文字检测）+ rec.onnx（CRNN 文字识别）
 * 模型，进程内推理，零外部服务依赖（替代原 PaddleOCR Docker 容器方案）。</p>
 *
 * <h3>处理链路</h3>
 * <pre>
 *   recognize(imageBytes)
 *     → 1. OpenCV 解码 + resize/padding（det 960×960）
 *     → 2. det ONNX 推理 → DB 二值化 → findContours → box 排序（自上而下）
 *     → 3. 对每个 box：透视矫正裁剪 → resize（rec 320×48）
 *     → 4. rec ONNX 推理 → argmax per step → CTC decode → charDict 映射
 *     → 5. 拼接返回 fullText + lineCount
 * </pre>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>模型文件不存在 → OcrResult.failed("model not found")</li>
 *   <li>ONNX Runtime 初始化失败 → OcrResult.failed("onnx init failed")</li>
 *   <li>OpenCV 解码失败 / 图片超 maxImageBytes → OcrResult.failed()</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class OnnxOcrClient {

    private final OnnxOcrProperties properties;
    private volatile boolean initialized = false;
    private volatile String initError = null;

    // --- ONNX Runtime 句柄 ---
    private OrtEnvironment ortEnv;
    private OrtSession detSession;
    private OrtSession recSession;
    private String detInputName;
    private String recInputName;
    private int detInputW;
    private int detInputH;
    private int recInputW;
    private int recInputH;

    // --- 字符字典：PaddleOCR ppocr_keys_v1.txt（6623 字） ---
    // index 0 → blank（CTC 跳过），index 1..N → charDict[i]
    private String[] charDict;
    private int recDictSize;

    public OnnxOcrClient(OnnxOcrProperties properties) {
        this.properties = properties;
    }

    // ====================================================================
    // 生命周期
    // ====================================================================

    @PostConstruct
    public synchronized void init() {
        if (!properties.isEnabled()) {
            log.info("OnnxOcrClient disabled (aegis.ocr.onnx.enabled=false)");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            // 1. OpenCV native lib
            nu.pattern.OpenCV.loadLocally();

            // 2. 字符字典
            loadCharDict();

            // 3. ONNX Runtime 全局环境
            ortEnv = OrtEnvironment.getEnvironment();

            // 4. Det 模型加载
            Path detPath = resolvePath(properties.getDetModelPath());
            recDictSize = charDict != null ? charDict.length : 6623;
            detInputW = properties.getDetInputWidth();
            detInputH = properties.getDetInputHeight();
            loadDetSession(detPath);

            // 5. Rec 模型加载
            Path recPath = resolvePath(properties.getRecModelPath());
            recInputW = properties.getRecInputWidth();
            recInputH = properties.getRecInputHeight();
            loadRecSession(recPath);

            initialized = true;
            log.info("OnnxOcrClient init OK: det={}x{}, rec={}x{}, chars={}, elapsed={}ms",
                    detInputW, detInputH, recInputW, recInputH, recDictSize,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            initError = e.getMessage();
            log.error("OnnxOcrClient init FAILED (ONNX OCR 降级不可用): {}", e.getMessage(), e);
            // initialized 保持 false，后续所有 recognize() 直接返回 OcrResult.failed()
        }
    }

    @PreDestroy
    public synchronized void destroy() {
        for (OrtSession s : new OrtSession[]{detSession, recSession}) {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
        detSession = null;
        recSession = null;
        if (ortEnv != null) {
            try { ortEnv.close(); } catch (Exception ignored) {}
            ortEnv = null;
        }
        initialized = false;
    }

    // ====================================================================
    // 公开 API
    // ====================================================================

    /**
     * 执行 OCR 识别（det + rec 两阶段）。
     *
     * @param imageBytes 原始图片字节
     * @param filename   文件名（仅日志）
     * @return OCR 结果（永不返回 null）
     */
    public OcrResult recognize(byte[] imageBytes, String filename) {
        if (!initialized) {
            String reason = initError != null ? "init failed: " + initError : "not initialized";
            log.debug("OnnxOcrClient skipped: {}", reason);
            return OcrResult.failed("ONNX OCR not ready: " + reason);
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrResult.failed("empty image");
        }
        if (imageBytes.length > properties.getMaxImageBytes()) {
            log.warn("图片超 ONNX OCR 上限: filename={}, sizeKB={}, limitKB={}",
                    filename, imageBytes.length / 1024, properties.getMaxImageBytes() / 1024);
            return OcrResult.failed("image too large");
        }

        long start = System.currentTimeMillis();
        Mat img;
        try {
            MatOfByte mob = new MatOfByte(imageBytes);
            img = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
        } catch (Exception e) {
            log.warn("OpenCV 解码失败: filename={}, error={}", filename, e.getMessage());
            return OcrResult.failed("image decode error: " + e.getMessage());
        }
        if (img.empty()) {
            return OcrResult.failed("empty image after decode");
        }

        try {
            // === Phase 1: DET 检测 ===
            List<float[]> boxes = detectTexts(img);
            if (boxes.isEmpty()) {
                log.info("ONNX OCR det 未找到文字区域: filename={}", filename);
                return OcrResult.failed("no text detected");
            }

            // === Phase 2: REC 逐框识别 ===
            List<String> lines = recognizeBoxes(img, boxes);
            // 过滤空结果
            List<String> valid = lines.stream().filter(s -> !s.isBlank()).collect(Collectors.toList());

            long elapsed = System.currentTimeMillis() - start;
            if (valid.isEmpty()) {
                log.info("ONNX OCR rec 无有效文字: filename={}, elapsed={}ms", filename, elapsed);
                return OcrResult.failed("recognized text empty");
            }

            String fullText = String.join("\n", valid);
            log.info("ONNX OCR 成功: filename={}, boxes={}, lines={}, chars={}, elapsed={}ms",
                    filename, boxes.size(), valid.size(), fullText.length(), elapsed);
            return OcrResult.success(fullText, valid.size(), elapsed);
        } catch (Exception e) {
            log.warn("ONNX OCR 推理异常: filename={}, error={}", filename, e.getMessage(), e);
            return OcrResult.failed("onnx inference error: " + e.getMessage());
        } finally {
            img.release();
        }
    }

    /**
     * 健康检查（ONNX 初始化 + 模型就绪）。
     */
    public boolean healthCheck() {
        return initialized;
    }

    // ====================================================================
    // Phase 1: Text Detection (DB)
    // ====================================================================

    /**
     * 从原图检测文字区域，返回四边形顶点数组（每个 box 是 8 个 float: x1,y1,x2,y2,x3,y3,x4,y4）。
     * 按 y 坐标自上而下排序。
     */
    private List<float[]> detectTexts(Mat img) throws OrtException {
        int origH = img.rows();
        int origW = img.cols();

        // 1. resize + padding 到 detInputW × detInputH（保持比例，短边 pad）
        Mat preprocessed = preprocessDet(img);

        // 2. 构造 ONNX 输入 tensor（N=1, C=3, H=detInputH, W=detInputW）
        float[] input = hwcToNchw(preprocessed);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put(detInputName, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input),
                new long[]{1, 3, detInputH, detInputW}));

        // 3. 推理
        try (OrtSession.Result result = detSession.run(inputs)) {
            // 输出: [1, 1, detInputH, detInputW] float
            float[][][][] pred = (float[][][][]) result.get(0).getValue();

            // 4. DB 阈值二值化 → 0/255 灰度图
            Mat binary = new Mat(detInputH, detInputW, CvType.CV_8UC1);
            float thr = properties.getDetThreshold();
            for (int y = 0; y < detInputH; y++) {
                byte[] row = new byte[detInputW];
                for (int x = 0; x < detInputW; x++) {
                    row[x] = pred[0][0][y][x] > thr ? (byte) 255 : 0;
                }
                binary.put(y, 0, row);
            }

            // 5. findContours → minAreaRect → 坐标还原到原图
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST,
                    Imgproc.CHAIN_APPROX_SIMPLE);

            float scaleX = (float) origW / detInputW;
            float scaleY = (float) origH / detInputH;
            float minArea = properties.getDetMinArea();

            List<float[]> boxes = new ArrayList<>();
            for (MatOfPoint c : contours) {
                double area = Imgproc.contourArea(c);
                if (area < minArea) continue;

                RotatedRect rect = Imgproc.minAreaRect(new MatOfPoint2f(c.toArray()));
                Point[] pts = new Point[4];
                rect.points(pts);

                // 还原到原图坐标 + 顺序化（左下/左上/右上/右下 → 排序）
                float[] box = new float[8];
                // 按 y 排序 + x 左对齐
                List<Point> sorted = Arrays.asList(pts);
                sorted.sort((a, b) -> {
                    if (Math.abs(a.y - b.y) > 5) return Double.compare(a.y, b.y);
                    return Double.compare(a.x, b.x);
                });
                box[0] = (float) sorted.get(0).x * scaleX;
                box[1] = (float) sorted.get(0).y * scaleY;
                box[2] = (float) sorted.get(1).x * scaleX;
                box[3] = (float) sorted.get(1).y * scaleY;
                box[4] = (float) sorted.get(2).x * scaleX;
                box[5] = (float) sorted.get(2).y * scaleY;
                box[6] = (float) sorted.get(3).x * scaleX;
                box[7] = (float) sorted.get(3).y * scaleY;
                boxes.add(box);
            }

            // 按 y 坐标自上而下排序
            boxes.sort(Comparator.comparingDouble(b -> (b[1] + b[3] + b[5] + b[7]) / 4.0));
            hierarchy.release();
            binary.release();
            preprocessed.release();
            return boxes;
        }
    }

    // ====================================================================
    // Phase 2: Text Recognition (CRNN)
    // ====================================================================

    private List<String> recognizeBoxes(Mat img, List<float[]> boxes) throws OrtException {
        List<String> results = new ArrayList<>();
        for (float[] box : boxes) {
            Mat cropped = perspectiveWarp(img, box);
            if (cropped.empty()) { results.add(""); continue; }

            Mat preprocessed = preprocessRec(cropped);
            float[] input = hwcToNchw(preprocessed);
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(recInputName, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input),
                    new long[]{1, 3, recInputH, recInputW}));

            try (OrtSession.Result result = recSession.run(inputs)) {
                float[][][] logits = (float[][][]) result.get(0).getValue();
                String text = decodeCtc(logits);
                results.add(text);
            } catch (OrtException e) {
                results.add("");
                log.debug("rec 推理异常: {}", e.getMessage());
            } finally {
                cropped.release();
                preprocessed.release();
            }
        }
        return results;
    }

    /**
     * CTC decode + 字符字典映射。
     *
     * <p>输入 logits: float[1][T][V]（T=时序步，V=vocab_size=6623+1=blank 在前）。
     * 对每步取 argmax，跳过重复 + 跳过 blank(0)，剩下 index-1 映射 charDict。</p>
     */
    private String decodeCtc(float[][][] logits) {
        float[][] seq = logits[0]; // [T][V]
        StringBuilder sb = new StringBuilder();
        int lastIdx = -1;
        for (float[] dist : seq) {
            // argmax
            int idx = 0;
            float max = dist[0];
            for (int i = 1; i < dist.length; i++) {
                if (dist[i] > max) { max = dist[i]; idx = i; }
            }
            if (idx == 0) { lastIdx = idx; continue; } // blank
            if (idx == lastIdx) continue;              // 重复
            // idx 从 1 开始对应 charDict[idx-1]
            if (idx - 1 < charDict.length) {
                sb.append(charDict[idx - 1]);
            }
            lastIdx = idx;
        }
        return sb.toString();
    }

    // ====================================================================
    // Preprocessing
    // ====================================================================

    /**
     * DET 预处理：RGB convert + resize 保持比例 + 右上 padding + normalize。
     */
    private Mat preprocessDet(Mat img) {
        int origH = img.rows();
        int origW = img.cols();

        // BGR → RGB（PaddleOCR 用 ImageNet mean/std on RGB）
        Mat rgb = new Mat();
        Imgproc.cvtColor(img, rgb, Imgproc.COLOR_BGR2RGB);

        // resize 保持短边对齐
        float ratio = Math.min((float) detInputW / origW, (float) detInputH / origH);
        int newW = Math.round(origW * ratio);
        int newH = Math.round(origH * ratio);
        Mat resized = new Mat();
        Imgproc.resize(rgb, resized, new Size(newW, newH));

        // padding：右上补到 detInputW × detInputH，BORDER_CONSTANT=0
        Mat padded = new Mat(detInputH, detInputW, CvType.CV_8UC3, new Scalar(0, 0, 0));
        resized.copyTo(padded.submat(0, newH, 0, newW));

        // normalize → float [0,1]，后续 hwcToNchw 会做 mean/std
        padded.convertTo(padded, CvType.CV_32FC3, 1.0 / 255.0);

        rgb.release();
        resized.release();
        return padded;
    }

    /**
     * REC 预处理：RGB convert + resize（保持宽度比例）+ padding。
     */
    private Mat preprocessRec(Mat img) {
        int origH = img.rows();
        int origW = img.cols();

        Mat rgb = new Mat();
        Imgproc.cvtColor(img, rgb, Imgproc.COLOR_BGR2RGB);

        // resize 保持高度到 recInputH，宽度按比例，再 padding 到 recInputW
        float ratio = (float) recInputH / origH;
        int newW = Math.round(origW * ratio);
        if (newW > recInputW) newW = recInputW; // 截断
        Mat resized = new Mat();
        Imgproc.resize(rgb, resized, new Size(newW, recInputH));

        Mat padded = new Mat(recInputH, recInputW, CvType.CV_8UC3, new Scalar(0, 0, 0));
        resized.copyTo(padded.submat(0, recInputH, 0, newW));
        padded.convertTo(padded, CvType.CV_32FC3, 1.0 / 255.0);

        rgb.release();
        resized.release();
        return padded;
    }

    /**
     * HWC float Mat → NCHW float[] + ImageNet normalize（det）/ [0,1]→[-1,1] normalize（rec）。
     * 根据 Mat 尺寸自动判断 det/rec。
     */
    private float[] hwcToNchw(Mat hwc) {
        int h = hwc.rows();
        int w = hwc.cols();
        float[] data = new float[3 * h * w];
        boolean isRec = (h == recInputH); // rec 和 det 高度不同

        float meanR = isRec ? 0.5f : 0.485f;
        float meanG = isRec ? 0.5f : 0.456f;
        float meanB = isRec ? 0.5f : 0.406f;
        float stdR  = isRec ? 0.5f : 0.229f;
        float stdG  = isRec ? 0.5f : 0.224f;
        float stdB  = isRec ? 0.5f : 0.225f;

        for (int y = 0; y < h; y++) {
            float[] row = new float[w * 3];
            hwc.get(y, 0, row); // Mat.get(row, col, float[]) 读取整行
            for (int x = 0; x < w; x++) {
                float r = row[x * 3]   > 0 ? row[x * 3]   : 0;
                float g = row[x * 3+1] > 0 ? row[x * 3+1] : 0;
                float b = row[x * 3+2] > 0 ? row[x * 3+2] : 0;
                // 注意 OpenCV RGB 顺序 → index 0=R, 1=G, 2=B（已在 cvtColor 转好）
                data[0 * h * w + y * w + x] = (r - meanR) / stdR;
                data[1 * h * w + y * w + x] = (g - meanG) / stdG;
                data[2 * h * w + y * w + x] = (b - meanB) / stdB;
            }
        }
        return data;
    }

    /**
     * 透视变换：4 点 box → 固定尺寸矩形（recInputW × recInputH）。
     */
    private Mat perspectiveWarp(Mat src, float[] box) {
        Point srcPt = new Point(box[0], box[1]);
        Point srcPt2 = new Point(box[2], box[3]);
        Point srcPt3 = new Point(box[4], box[5]);
        Point srcPt4 = new Point(box[6], box[7]);

        // 目标矩形（从左到右排序：左上→右上→右下→左下）
        Point[] srcArr = {srcPt, srcPt2, srcPt3, srcPt4};
        Point[] ordered = orderPoints(srcArr);
        Point[] dstArr = {
                new Point(0, 0),
                new Point(recInputW - 1, 0),
                new Point(recInputW - 1, recInputH - 1),
                new Point(0, recInputH - 1)
        };

        Mat M = Imgproc.getPerspectiveTransform(new MatOfPoint2f(ordered), new MatOfPoint2f(dstArr));
        Mat dst = new Mat();
        Imgproc.warpPerspective(src, dst, M, new Size(recInputW, recInputH));
        M.release();
        return dst;
    }

    private Point[] orderPoints(Point[] pts) {
        // 左上：x+y 最小；右下：x+y 最大
        Point tl = pts[0], br = pts[0];
        double minSum = Double.MAX_VALUE, maxSum = -Double.MAX_VALUE;
        for (Point p : pts) {
            double s = p.x + p.y;
            if (s < minSum) { minSum = s; tl = p; }
            if (s > maxSum) { maxSum = s; br = p; }
        }
        // 右上：x-y 最小；左下：x-y 最大
        Point tr = pts[0], bl = pts[0];
        double minDiff = Double.MAX_VALUE, maxDiff = -Double.MAX_VALUE;
        for (Point p : pts) {
            double d = p.x - p.y;
            if (d < minDiff) { minDiff = d; tr = p; }
            if (d > maxDiff) { maxDiff = d; bl = p; }
        }
        return new Point[]{tl, tr, br, bl};
    }

    // ====================================================================
    // ONNX Session Loader
    // ====================================================================

    private void loadDetSession(Path detPath) throws OrtException, IOException {
        if (!Files.exists(detPath)) {
            throw new FileNotFoundException("det model not found: " + detPath.toAbsolutePath());
        }
        OrtSession.SessionOptions opts = buildSessionOptions();
        detSession = ortEnv.createSession(detPath.toAbsolutePath().toString(), opts);
        detInputName = detSession.getInputNames().iterator().next();
        log.info("det session OK: path={}, inputName={}", detPath.getFileName(), detInputName);
    }

    private void loadRecSession(Path recPath) throws OrtException, IOException {
        if (!Files.exists(recPath)) {
            throw new FileNotFoundException("rec model not found: " + recPath.toAbsolutePath());
        }
        OrtSession.SessionOptions opts = buildSessionOptions();
        recSession = ortEnv.createSession(recPath.toAbsolutePath().toString(), opts);
        recInputName = recSession.getInputNames().iterator().next();
        log.info("rec session OK: path={}, inputName={}", recPath.getFileName(), recInputName);
    }

    private OrtSession.SessionOptions buildSessionOptions() {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        try {
            if (properties.isGraphOptimize()) {
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            }
            int threads = properties.getNumThreads() > 0
                    ? properties.getNumThreads()
                    : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            opts.setIntraOpNumThreads(threads);
        } catch (Exception e) {
            log.debug("ONNX session options adjusted: {}", e.getMessage());
        }
        return opts;
    }

    // ====================================================================
    // Utility
    // ====================================================================

    private Path resolvePath(String path) {
        Path p = Path.of(path);
        if (Files.exists(p)) return p.toAbsolutePath();
        // 尝试相对项目根
        Path alt = Path.of(".").resolve(path);
        if (Files.exists(alt)) return alt.toAbsolutePath();
        return p.toAbsolutePath();
    }

    private void loadCharDict() throws IOException {
        Path dictPath = resolvePath(properties.getCharDictPath());
        if (!Files.exists(dictPath)) {
            throw new FileNotFoundException("char dict not found: " + dictPath.toAbsolutePath()
                    + " — 请先运行 download-ocr-models.ps1 下载");
        }
        List<String> lines = Files.readAllLines(dictPath);
        // PaddleOCR dict 每行一个字符，第 0 行是 CJK 统一汉字的起始（index 0 对应 dict[0]）
        // 但 CTC blank 在 index=0，所以我们保留原样并在 decode 时做 idx-1 映射
        charDict = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            charDict[i] = lines.get(i).trim();
        }
        log.info("OnnxOcrClient charDict loaded: {} entries from {}", charDict.length, dictPath.getFileName());
    }
}
