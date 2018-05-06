package com.walker.nldwd;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class Database {
    private Connection connection;

    private PreparedStatement getServer;
    private PreparedStatement updateServerChannel;
    private PreparedStatement insertServer;

    private PreparedStatement getTrackedStream;
    private PreparedStatement updateTrackedStream;
    private PreparedStatement insertTrackedStream;

    public Database() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:database.db");

        getServer = connection.prepareStatement("SELECT * FROM servers WHERE id = ?");
        updateServerChannel = connection.prepareStatement("UPDATE servers SET channel = ?, webhook_id = ? WHERE id = ?");
        insertServer = connection.prepareStatement("INSERT INTO servers (id, channel, webhook_id) VALUES (?, ?, ?)");

        getTrackedStream = connection.prepareStatement("SELECT * FROM streams WHERE id = ?");
        updateTrackedStream = connection.prepareStatement("UPDATE streams SET tracking_pool = ? WHERE id = ?");
        insertTrackedStream = connection.prepareStatement("INSERT INTO streams(`id`,`tracking_pool`) VALUES (?,?)");
    }

    public boolean hasServer(long server) throws SQLException {
        getServer.setLong(1, server);
        return getServer.executeQuery().next();
    }

    public void setChannel(long server, long channel, long websocketId) throws SQLException {
        updateServerChannel.setLong(1, channel);
        updateServerChannel.setLong(2, websocketId);
        updateServerChannel.setLong(3, server);
        updateServerChannel.execute();
    }

    public void insertNewServer(long server, long channel, long websocketId) throws SQLException {
        insertServer.setLong(1, server);
        insertServer.setLong(2, channel);
        insertServer.setLong(3, websocketId);
        insertServer.execute();
    }

    public long getWebsocket(long server) throws SQLException {
        getServer.setLong(1, server);
        ResultSet rs = getServer.executeQuery();
        if(rs.next()) {
            return rs.getLong("webhook_id");
        }
        return -1;
    }

    public Optional<Long> getChannelInline(long server) {
        try {
            long res = getChannel(server);
            if(res != -1) {
                return Optional.of(res);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public long getChannel(long server) throws SQLException {
        getServer.setLong(1, server);
        ResultSet rs = getServer.executeQuery();
        if(rs.next()) {
            return rs.getLong("channel");
        }
        return -1;
    }

    public boolean isTrackingStream(long stream) throws SQLException {
        getTrackedStream.setLong(1, stream);
        return getTrackedStream.executeQuery().next();
    }

    public void addStreamTrack(long stream, long server) throws SQLException {
        getTrackedStream.setLong(1, stream);
        ResultSet rs = getTrackedStream.executeQuery();
        if(rs.next()){
            JsonArray array = Json.parse(rs.getString("tracking_pool")).asArray();
            if(array.values().stream().noneMatch(value -> value.asLong() == server)){
                array.add(server);
                updateTrackedStream.setString(1, array.toString());
                updateTrackedStream.setLong(2, stream);
                updateTrackedStream.execute();
            }
        }
    }

    public void createNewStreamTrack(long stream, long server) throws SQLException {
        insertTrackedStream.setLong(1, stream);
        insertTrackedStream.setString(2, Json.array().add(server).toString());
        System.out.println("Insert result: " + insertTrackedStream.execute());
    }

    public List<Long> getServersTrackingStream(long stream) throws SQLException {
        getTrackedStream.setLong(1, stream);
        ResultSet rs = getTrackedStream.executeQuery();
        if(rs.next()){
            return Json.parse(rs.getString("tracking_pool"))
                    .asArray().values().stream().map(JsonValue::asLong).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
