package io.portfolio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.*;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.config.annotation.*;
import java.util.List;

@Configuration @EnableWebSocketMessageBroker
class Live implements WebSocketMessageBrokerConfigurer {
    final JwtDecoder decoder;
    final String origin;
    Live(JwtDecoder decoder,@Value("${app.origin}") String origin) { this.decoder=decoder;this.origin=origin; }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint=registry.addEndpoint("/ws");
        if(!origin.isBlank()) endpoint.setAllowedOrigins(origin);
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue"); registry.setApplicationDestinationPrefixes("/app");
    }
    @Override public void configureClientInboundChannel(ChannelRegistration channel) {
        channel.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message,MessageChannel target) {
                var stomp=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
                if(stomp==null||stomp.getCommand()==null) return message;
                if(stomp.getCommand()==StompCommand.CONNECT) {
                    String token=stomp.getFirstNativeHeader("Authorization");
                    if(token==null||!token.startsWith("Bearer ")) throw new IllegalArgumentException("Sign in to subscribe.");
                    var user=decoder.decode(token.substring(7));
                    stomp.setUser(new UsernamePasswordAuthenticationToken(user.getSubject(),null,List.of()));
                } else if(stomp.getCommand()==StompCommand.SUBSCRIBE) {
                    if(stomp.getUser()==null||!"/user/queue/live".equals(stomp.getDestination())) throw new IllegalArgumentException("Invalid subscription.");
                } else if(stomp.getCommand()==StompCommand.SEND) {
                    if(stomp.getUser()==null||!"/app/ping".equals(stomp.getDestination())) throw new IllegalArgumentException("Invalid destination.");
                }
                return message;
            }
        });
    }
}

@Controller
class LiveEvents {
    final SimpMessagingTemplate bus;
    LiveEvents(SimpMessagingTemplate bus) { this.bus=bus; }
    record Event(String type,String rideId) {}
    @MessageMapping("/ping") @SendToUser(value="/queue/live",broadcast=false)
    Event ping() { return new Event("READY",null); }
    @TransactionalEventListener
    void publish(Notice event) {
        for(String id:event.users()) bus.convertAndSendToUser(id,"/queue/live",new Event(event.type(),event.ride()));
    }
}
