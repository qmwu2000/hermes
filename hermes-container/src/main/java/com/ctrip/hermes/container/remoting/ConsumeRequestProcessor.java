package com.ctrip.hermes.container.remoting;

import java.util.Arrays;
import java.util.List;

import org.unidal.lookup.annotation.Inject;

import com.alibaba.fastjson.JSON;
import com.ctrip.hermes.engine.ConsumerBootstrap;
import com.ctrip.hermes.engine.MessageContext;
import com.ctrip.hermes.remoting.Command;
import com.ctrip.hermes.remoting.CommandContext;
import com.ctrip.hermes.remoting.CommandProcessor;
import com.ctrip.hermes.remoting.CommandType;
import com.ctrip.hermes.storage.message.Message;

public class ConsumeRequestProcessor implements CommandProcessor {

	public static final String ID = "sonsume-request";

	@Inject
	private ConsumerBootstrap m_consumerManager;

	@Override
	public List<CommandType> commandTypes() {
		return Arrays.asList(CommandType.ConsumeRequest);
	}

	@Override
	public void process(CommandContext ctx) {
		Command cmd = ctx.getCommand();
		String topic = cmd.getHeader("topic");

		// TODO parse cmd.getBody() to multiple message bytes
		Message msg = JSON.parseObject(cmd.getBody(), Message.class);
		MessageContext msgCtx = new MessageContext(topic, Arrays.asList(msg));

		m_consumerManager.deliverMessage(cmd.getCorrelationId(), msgCtx);
	}

}
