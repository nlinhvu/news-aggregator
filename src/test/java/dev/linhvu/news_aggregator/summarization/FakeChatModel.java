package dev.linhvu.news_aggregator.summarization;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Trả về một chuỗi cố định, hoặc ném nếu được dựng với exception. Ghi lại prompt
 * cuối cùng để test khẳng định excerpt thật sự đi vào prompt.
 */
class FakeChatModel implements ChatModel {

	private final String output;

	private final RuntimeException failure;

	volatile String lastPrompt;

	volatile int calls;

	FakeChatModel(String output) {
		this(output, null);
	}

	FakeChatModel(String output, RuntimeException failure) {
		this.output = output;
		this.failure = failure;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		this.lastPrompt = prompt.getContents();
		this.calls++;
		if (failure != null) {
			throw failure;
		}
		return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
	}
}
