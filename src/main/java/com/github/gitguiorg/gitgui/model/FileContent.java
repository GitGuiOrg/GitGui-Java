package com.github.gitguiorg.gitgui.model;

/**
 * The blob of one file at a commit.
 *
 * @param size      size in bytes ({@code data} is empty when truncated)
 * @param binary    the first bytes contain a NUL, so the file is not shown as text
 * @param truncated the file is larger than the preview limit and {@code data} was not read
 */
public record FileContent(long size, boolean binary, boolean truncated, byte[] data) {
    public String text() {
        return com.github.gitguiorg.gitgui.util.TextEncoding.decodeToString(data);
    }
}
