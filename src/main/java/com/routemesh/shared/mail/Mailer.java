package com.routemesh.shared.mail;

import java.util.List;

public interface Mailer {

    void send(List<String> to, String subject, String body, Attachment attachment);

    record Attachment(String filename, String contentType, byte[] content) {
    }
}
