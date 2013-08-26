/**
 *
 * Copyright (c) 2009-2013 Freedomotic team http://freedomotic.com
 *
 * This file is part of Freedomotic
 *
 * This Program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2, or (at your option) any later version.
 *
 * This Program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Freedomotic; see the file COPYING. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package it.freedomotic.bus;

import com.google.inject.Inject;
import it.freedomotic.app.Freedomotic;
import it.freedomotic.app.AppConfig;
import it.freedomotic.app.Profiler;
import static it.freedomotic.bus.AbstractBusConnector.sendSession;

import it.freedomotic.reactions.Command;

import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;

import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jms.*;

public final class CommandChannel
        extends AbstractBusConnector
        implements MessageListener {

    private BusConsumer handler;
    private MessageProducer producer;
    private MessageConsumer consumer;
    //private static ExecutorService executor;
    private String channelName;
    @Inject
    AppConfig config;

    public CommandChannel() {
        super();
        buildChannel();
    }

    public CommandChannel(String CHANNEL_URL) {
        super();
        buildChannel();
        consumeFrom(CHANNEL_URL);
    }

    private void buildChannel() {
        //executor = Executors.newCachedThreadPool();
        try {
            producer = sendSession.createProducer(null);
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
        } catch (JMSException ex) {
            Logger.getLogger(CommandChannel.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void consumeFrom(String channelName) {
        if (handler != null) {
            try {
                Destination queue = receiveSession.createQueue(channelName /*
                         * + "?consumer.exclusive=true"
                         */);
                this.channelName = channelName;
                consumer = receiveSession.createConsumer(queue);
                consumer.setMessageListener(this);
                LOG.config(getHandler().getClass().getSimpleName() + " listen on "
                        + queue.toString());
            } catch (javax.jms.JMSException jmse) {
                LOG.severe(Freedomotic.getStackTraceInfo(jmse));
            }
        } else {
            LOG.severe("No handler for command channel " + channelName);
        }
    }

    public void reply(Command command, Destination channel, String correlationID) {
        try {
            ObjectMessage msg = sendSession.createObjectMessage();
            msg.setObject(command);
            msg.setJMSCorrelationID(correlationID);
            producer.setTimeToLive(config.getIntProperty("KEY_MESSAGES_TTL", 1000));
            Profiler.incrementSentReplies();
            producer.send(channel, msg);
        } catch (JMSException jmse) {
            LOG.severe(Freedomotic.getStackTraceInfo(jmse));
        }
    }

    public Command send(final Command command) {
        try {
            ObjectMessage msg = sendSession.createObjectMessage();
            msg.setObject(command);

            Queue destination = new ActiveMQQueue(command.getReceiver());

            if (command.getReplyTimeout() > 0) {
                //we have to wait an execution reply for an hardware device or an external client
                producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

                ActiveMQDestination temporaryQueue = (ActiveMQDestination) unlistenedSession.createTemporaryQueue();
                msg.setJMSReplyTo(temporaryQueue);

                //a temporary consumer on a temporary queue
                MessageConsumer responseConsumer = unlistenedSession.createConsumer(temporaryQueue);
                producer.send(destination, msg);
                Profiler.incrementSentCommands();
                //the receive() call is blocking so we execute it in a thread
                LOG.config("Send and await reply to command '" + command.getName() + "' for "
                        + command.getReplyTimeout() + "ms");

                Message jmsResponse = responseConsumer.receive(command.getReplyTimeout());

                if (jmsResponse != null) {
                    ObjectMessage objMessage = (ObjectMessage) jmsResponse;

                    //a command is sent, we expect a command as reply
                    Command reply = (Command) objMessage.getObject();
                    LOG.config("Reply to command '" + command.getName() + "' received. Result is "
                            + reply.getProperty("result"));
                    Profiler.incrementReceivedReplies();

                    return reply;
                } else {
                    LOG.config("Command '" + command.getName() + "' timed out after "
                            + command.getReplyTimeout() + "ms");
                    Profiler.incrementTimeoutedReplies();
                }

                command.setExecuted(false); //mark as failed

                return command; //returns back the original inaltered command
            } else {
                //send the message immediately without creating temporary queues and consumers on it
                //this increments perfornances if no reply is expected
                producer.send(destination, msg);
                Profiler.incrementSentCommands();
                command.setExecuted(true);

                return command; //always say it is executed (it's not sure but the caller is not interested: best effort)
            }
        } catch (JMSException ex) {
            LOG.severe(Freedomotic.getStackTraceInfo(ex));
            command.setExecuted(false);

            return command;
        }
    }

    private BusConsumer getHandler() {
        return handler;
    }

    public void setHandler(BusConsumer handler) {
        this.handler = handler;
    }

    /**
     * Freedomotic receives something from the bus. It can be an event or a
     * command. This is the bus hook for any java class that use the freedomotic
     * bus. Classes register itself as handlers on this class.
     *
     * @param aMessage
     */
    @Override
    public final void onMessage(final Message aMessage) {
        Profiler.incrementReceivedCommands();

        if (handler != null) {
            if (aMessage instanceof ObjectMessage) {
                getHandler().onMessage((ObjectMessage) aMessage);
            } else {
                LOG.severe("A message is received by " + channelName
                        + " but it is not an object message is a "
                        + aMessage.getClass().getCanonicalName());

                TextMessage text = (TextMessage) aMessage;

                try {
                    System.out.println(text.getText());
                } catch (JMSException ex) {
                    Logger.getLogger(CommandChannel.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        } else {
            LOG.warning("No handler specified for this command channel");
        }
    }

    private String createRandomString() {
        Random random = new Random(System.currentTimeMillis());
        long randomLong = random.nextLong();

        return Long.toHexString(randomLong);
    }

    public void unsubscribe() {
        try {
            producer.close();
            consumer.close();
        } catch (JMSException ex) {
            LOG.severe("Unable to unsubscribe from channel " + channelName);
        }
    }
    private static final Logger LOG = Logger.getLogger(CommandChannel.class.getName());
}