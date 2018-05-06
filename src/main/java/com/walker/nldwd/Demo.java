package com.walker.nldwd;

import okhttp3.OkHttpClient;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.webhook.Webhook;
import org.javacord.api.util.logging.ExceptionLogger;

import java.awt.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

public class Demo {

    static class NLException extends Exception {
        NLException(String message){
            super(message);
        }
    }

    public static void main(String[] args) throws IOException, SQLException {
        if(args.length < 1){
            System.err.println("Usage: java -jar {jar name} {discord token} {twitch token} {twitch secret} {twitch callback url} {twitch port}");
            System.exit(-1);
        }
        final Database database = new Database();
        Runtime.getRuntime().addShutdownHook(new Thread(database::close));
        final OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        final DiscordApi api = new DiscordApiBuilder().setToken(args[0]).login().join();
        final WebhookInteraction webhookInteraction = new WebhookInteraction(database, okHttpClient, api);
        final TwitchInteraction twitch = new TwitchInteraction(args[1], args[2], args[3], Integer.parseInt(args[4]), database, okHttpClient, webhookInteraction);

        AtomicLong atomicLong = new AtomicLong();
        api.getServerTextChannelById(442805495418585088L).orElseThrow(() -> new NullPointerException("Uhhh")).getWebhooks().join().forEach(webhook -> {
            System.out.println(webhook.getId());
            atomicLong.set(webhook.getId());
        });
        api.getWebhookById(atomicLong.get()).join();

        api.addMessageCreateListener(event -> {
            String[] tree = event.getMessage().getContent().split(" ");
            if(tree[0].equalsIgnoreCase("?demo") && tree.length > 1) {
                event.getServer().ifPresentOrElse(server -> {
                    if (tree[1].equalsIgnoreCase("subscribe")) {
                        if (tree.length > 2) {
                            try {
                                twitch.subscribe(server.getId(), tree[2]);
                                event.getChannel().sendMessage(new EmbedBuilder().setColor(Color.GREEN)
                                        .addField("Subscribed!", "Subscribed to " + tree[2] + " in channel " + database.getChannelInline(server.getId())
                                                .flatMap(server::getChannelById)
                                                .map(ServerChannel::getName)
                                                .orElse("{not yet set}")));
                            } catch (IOException | NLException | SQLException e) {
                                event.getChannel().sendMessage(new EmbedBuilder().setColor(Color.RED).addField("Subscribe failed.", e.getMessage()));
                            }
                        } else {
                            event.getChannel().sendMessage(new EmbedBuilder().setColor(Color.RED).addField("Subscribe failed.", "Please specify a twitch channel"));
                        }
                    } else if (tree[1].equalsIgnoreCase("setchannel")) {
                        try {
                            if (!event.getMessage().getMentionedChannels().isEmpty()) {
                                ServerTextChannel channel = event.getMessage().getMentionedChannels().get(0);
                                if (database.hasServer(server.getId())) {
                                    api.getWebhookById(database.getWebsocket(server.getId())).join().delete().exceptionally(ExceptionLogger.get());
                                    Webhook newWebhook = channel.createWebhookBuilder().setName("NLDoubleWebhookDemo").create().join();
                                    database.setChannel(server.getId(), channel.getId(), newWebhook.getId());
                                } else {
                                    Webhook newWebhook = channel.createWebhookBuilder().setName("NLDoubleWebhookDemo").create().join();
                                    database.insertNewServer(server.getId(), channel.getId(), newWebhook.getId());
                                }
                                event.getChannel().sendMessage(new EmbedBuilder().setColor(Color.GREEN).addField("Set!", "Set announcements channel to  " + event.getMessage().getMentionedChannels().get(0).getName()));
                            } else {
                                event.getChannel().sendMessage(new EmbedBuilder().setColor(Color.RED).addField("Set channel failed.", "Please specify a channel"));
                            }
                        } catch (SQLException e){
                            e.printStackTrace();
                            event.getChannel().sendMessage(new EmbedBuilder().setColor(Color.RED).addField("Set channel failed.", "Server error"));
                        }
                    }
                }, () -> event.getChannel().sendMessage(new EmbedBuilder().setColor(Color.RED).addField("Command failed.", "Please run this command in a server")));
            }
        });
    }
}
