/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jun.mqttx.broker.handler;

import com.jun.mqttx.config.MqttxConfig;
import com.jun.mqttx.service.ISubscriptionService;
import com.jun.mqttx.utils.TopicUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.mqtt.*;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link MqttMessageType#UNSUBSCRIBE} 消息处理器
 *
 * @author Jun
 * @since 1.0.4
 */
@Handler(type = MqttMessageType.UNSUBSCRIBE)
public class UnsubscribeHandler extends AbstractMqttSessionHandler {

    private final Boolean enableSysTopic;
    private final ISubscriptionService subscriptionService;

    public UnsubscribeHandler(MqttxConfig config, ISubscriptionService subscriptionService) {
        super(config.getCluster().getEnable());
        this.enableSysTopic = config.getSysTopic().getEnable();
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void process(ChannelHandlerContext ctx, MqttMessage msg) {
        MqttUnsubscribeMessage mqttUnsubscribeMessage = (MqttUnsubscribeMessage) msg;
        int messageId = mqttUnsubscribeMessage.variableHeader().messageId();
        MqttUnsubscribePayload payload = mqttUnsubscribeMessage.payload();

        // 系统主题
        List<String> collect = new ArrayList<>(payload.topics());
        if (enableSysTopic) {
            List<String> unSubSysTopics = collect.stream().filter(TopicUtils::isSys).collect(Collectors.toList());
            collect.removeAll(unSubSysTopics);
            Mono.when(unsubscribeSysTopics(unSubSysTopics, ctx), subscriptionService.unsubscribe(clientId(ctx), isCleanSession(ctx), collect))
                    .doOnSuccess(unused -> {
                        // response
                        MqttMessage mqttMessage = MqttMessageFactory.newMessage(
                                new MqttFixedHeader(MqttMessageType.UNSUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
                                MqttMessageIdVariableHeader.from(messageId),
                                null
                        );
                        ctx.writeAndFlush(mqttMessage);
                    }).subscribe();
            return;
        }

        // 非系统主题
        subscriptionService.unsubscribe(clientId(ctx), isCleanSession(ctx), collect)
                .doOnSuccess(unused -> {
                    // response
                    MqttMessage mqttMessage = MqttMessageFactory.newMessage(
                            new MqttFixedHeader(MqttMessageType.UNSUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
                            MqttMessageIdVariableHeader.from(messageId),
                            null
                    );
                    ctx.writeAndFlush(mqttMessage);
                }).subscribe();
    }

    /**
     * 系统主题订阅处理. 系统主题订阅没有持久化，仅保存在内存，需要单独处理.
     *
     * @param unSubSysTopics 解除订阅的主题列表
     * @param ctx            {@link ChannelHandlerContext}
     */
    private Mono<Void> unsubscribeSysTopics(List<String> unSubSysTopics, ChannelHandlerContext ctx) {
        return subscriptionService.unsubscribeSys(clientId(ctx), unSubSysTopics);
    }
}
