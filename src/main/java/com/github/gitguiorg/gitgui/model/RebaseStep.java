package com.github.gitguiorg.gitgui.model;

/**
 * @param newMessage the full replacement message: for {@link RebaseAction#REWORD} it is the commit's message,
 *                   for {@link RebaseAction#SQUASH} it replaces the message of the whole squash group
 *                   (and is expected on the group's last squash)
 */
public record RebaseStep(RebaseAction action, String hash, String subject, String newMessage) {}
