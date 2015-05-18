package com.ctrip.hermes.broker.build;

import java.util.ArrayList;
import java.util.List;

import org.unidal.dal.jdbc.configuration.AbstractJdbcResourceConfigurator;
import org.unidal.dal.jdbc.mapping.TableProvider;
import org.unidal.lookup.configuration.Component;

import com.ctrip.hermes.broker.ack.AckManager;
import com.ctrip.hermes.broker.ack.DefaultAckManager;
import com.ctrip.hermes.broker.bootstrap.DefaultBrokerBootstrap;
import com.ctrip.hermes.broker.config.BrokerConfig;
import com.ctrip.hermes.broker.lease.BrokerLeaseContainer;
import com.ctrip.hermes.broker.lease.BrokerLeaseManager;
import com.ctrip.hermes.broker.longpolling.LongPollingService;
import com.ctrip.hermes.broker.longpolling.SingleThreadLoopLongPollingService;
import com.ctrip.hermes.broker.queue.DefaultMessageQueueManager;
import com.ctrip.hermes.broker.queue.MessageQueueManager;
import com.ctrip.hermes.broker.queue.MessageQueuePartitionFactory;
import com.ctrip.hermes.broker.queue.storage.mysql.MySQLMessageQueueStorage;
import com.ctrip.hermes.broker.queue.storage.mysql.dal.HermesTableProvider;
import com.ctrip.hermes.broker.queue.storage.mysql.dal.MessageDataSourceProvider;
import com.ctrip.hermes.broker.transport.NettyServer;
import com.ctrip.hermes.broker.transport.NettyServerConfig;
import com.ctrip.hermes.broker.transport.command.processor.AckMessageCommandProcessor;
import com.ctrip.hermes.broker.transport.command.processor.PullMessageCommandProcessor;
import com.ctrip.hermes.broker.transport.command.processor.SendMessageCommandProcessor;
import com.ctrip.hermes.core.meta.MetaService;
import com.ctrip.hermes.core.transport.command.CommandType;
import com.ctrip.hermes.core.transport.command.processor.CommandProcessor;

public class ComponentsConfigurator extends AbstractJdbcResourceConfigurator {

	@Override
	public List<Component> defineComponents() {
		List<Component> all = new ArrayList<Component>();

		all.add(A(BrokerConfig.class));

		all.add(A(DefaultBrokerBootstrap.class));
		all.add(A(NettyServer.class));
		all.add(A(NettyServerConfig.class));

		all.add(C(CommandProcessor.class, CommandType.MESSAGE_SEND.toString(), SendMessageCommandProcessor.class)//
		      .req(MessageQueueManager.class)//
		      .req(BrokerLeaseContainer.class)//
		      .req(BrokerConfig.class)//
		);
		all.add(C(CommandProcessor.class, CommandType.MESSAGE_PULL.toString(), PullMessageCommandProcessor.class)//
		      .req(LongPollingService.class)//
		      .req(BrokerLeaseContainer.class)//
		      .req(BrokerConfig.class)//
		      );
		all.add(C(CommandProcessor.class, CommandType.MESSAGE_ACK.toString(), AckMessageCommandProcessor.class)//
		      .req(AckManager.class));

		all.add(A(SingleThreadLoopLongPollingService.class));
		all.add(A(BrokerLeaseManager.class));
		all.add(A(BrokerLeaseContainer.class));

		all.add(A(MessageQueuePartitionFactory.class));
		all.add(A(DefaultMessageQueueManager.class));
		all.add(A(DefaultAckManager.class));
		all.add(A(MySQLMessageQueueStorage.class));

		all.add(C(TableProvider.class, "message-priority", HermesTableProvider.class) //
		      .req(MetaService.class));
		all.add(C(TableProvider.class, "resend-group-id", HermesTableProvider.class) //
		      .req(MetaService.class));
		all.add(C(TableProvider.class, "offset-message", HermesTableProvider.class) //
		      .req(MetaService.class));
		all.add(C(TableProvider.class, "offset-resend", HermesTableProvider.class) //
		      .req(MetaService.class));
		all.add(C(TableProvider.class, "dead-letter", HermesTableProvider.class) //
		      .req(MetaService.class));

		all.add(A(MessageDataSourceProvider.class));

		all.addAll(new HermesDatabaseConfigurator().defineComponents());

		// Please keep it as last
		all.addAll(new WebComponentConfigurator().defineComponents());

		return all;
	}

	public static void main(String[] args) {
		generatePlexusComponentsXmlFile(new ComponentsConfigurator());
	}
}
