package com.aegis.runtime.infrastructure.document;

/**
 * 不支持的文件类型异常。
 *
 * @author wang.zhen
 */
public class UnsupportedFileTypeException extends RuntimeException {

    private final String extension;

    public UnsupportedFileTypeException(String extension) {
        super("Unsupported file type: " + extension);
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }
}
