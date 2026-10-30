package com.routemesh.shared.mail;

import com.routemesh.shared.config.RouteMeshProperties;
import jakarta.mail.MessagingException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    SmtpMailer(JavaMailSender sender, RouteMeshProperties props) {
        this.sender = sender;
        this.from = props.mail().from();
    }

    @Override
    public void send(List<String> to, String subject, String body, Attachment attachment) {
        try {
            var msg = sender.createMimeMessage();
            var helper = new MimeMessageHelper(msg, attachment != null, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to.toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText(body, false);
            if (attachment != null) {
                helper.addAttachment(attachment.filename(), new ByteArrayResource(attachment.content()), attachment.contentType());
            }
            sender.send(msg);
        } catch (MessagingException e) {
            throw new MailSendException("could not build message", e);
        }
    }
}
