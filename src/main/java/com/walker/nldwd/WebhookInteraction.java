package com.walker.nldwd;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.core.entity.message.embed.EmbedBuilderDelegateImpl;

import java.io.IOException;
import java.sql.SQLException;

public class WebhookInteraction {
    private static final String WEBSOCKET_POST_URL = "https://discordapp.com/api/v6/webhooks/";

    private final Database database;
    private final OkHttpClient client;
    private final DiscordApi discordApi;

    public WebhookInteraction(Database database, OkHttpClient client, DiscordApi api){
        this.database = database;
        this.client = new OkHttpClient();
        this.discordApi = api;
    }

    public void notifyStream(String name, long id, String game, String streamTitle) throws Demo.NLException, SQLException, IOException {
        try {
            for (Long serverId : database.getServersTrackingStream(id)) {
                String embedText = ((EmbedBuilderDelegateImpl) new EmbedBuilder()
                        .setColor(new java.awt.Color(100, 65, 164))
                        .setTitle(name + " is now live on Twitch!")
                        .addField("Now Playing", game, true)
                        .addField("Stream Title", streamTitle)
                        .setTimestamp().getDelegate()).toJsonNode().toString();

                JsonObject data = Json.object()
                        .set("username", "NLDoubleWebhookDemo")
                        .set("embeds", Json.array().add(Json.parse(embedText).asObject()));

                long websocketId = database.getWebsocket(serverId);

                Request webhookPost = new Request.Builder()
                        .url(WEBSOCKET_POST_URL + websocketId + "/" + discordApi.getWebhookById(websocketId)
                                .join()
                                .getToken()
                                .orElseThrow(() -> new Demo.NLException("Not enough permissions to announce in channel.")))
                        .post(RequestBody.create(MediaType.parse("application/json"), data.toString())).build();

                client.newCall(webhookPost).execute();
            }
        } catch (Exception e){
            e.printStackTrace();
            throw e;
        }
    }
}
