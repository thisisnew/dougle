package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackReplyAccess;
import dev.knowledgeforge.plugins.slack.application.SlackReplyService;
import dev.knowledgeforge.plugins.slack.application.SlackReplyTransport;
import org.springframework.stereotype.Component;

@Component
public class SlackReplyHttpTransport implements SlackReplyTransport {
    @Override public Result post(SlackReplyAccess.Lease lease, String body) {
        throw SlackReplyService.readOnly();
    }
}
