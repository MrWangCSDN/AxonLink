package com.axonlink.notification.service;

public record MailAttachment(String fileName, byte[] content, String contentType) {
}
