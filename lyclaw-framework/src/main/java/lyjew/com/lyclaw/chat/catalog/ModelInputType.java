package lyjew.com.lyclaw.chat.catalog;

/**
 * 模型支持的输入模态类型。
 *
 * <p>用于描述一个模型可以接收的输入形式，
 * 例如纯文本、图片、音频、视频或文档。
 */
public enum ModelInputType {

    /** 纯文本输入 */
    TEXT,

    /** 图片输入（base64 或 URL） */
    IMAGE,

    /** 音频输入 */
    AUDIO,

    /** 视频输入 */
    VIDEO,

    /** 文档输入（PDF、DOCX 等） */
    DOCUMENT
}
