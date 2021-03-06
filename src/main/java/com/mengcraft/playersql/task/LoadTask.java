package com.mengcraft.playersql.task;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.bukkit.Bukkit;

import com.mengcraft.playersql.NonBungeeModePlayerListener;
import com.mengcraft.playersql.PlayerManager;
import com.mengcraft.playersql.PlayerZQL;
import com.mengcraft.playersql.SyncManager.State;
import com.mengcraft.playersql.jdbc.ConnectionManager;

public class LoadTask implements Runnable {
    
    private static final String SELECT;
    private static final String INSERT;
    private static final String UPDATE;

    static {
        SELECT = "SELECT `Data`,`Online`,`Last` FROM `PlayerData` " +
                "WHERE `Player` = ?";
        INSERT = "INSERT INTO `PlayerData`(`Player`,`Online`,`LAST`) " +
                "VALUES(?,1,?)";
        UPDATE = "UPDATE `PlayerData` SET `Online` = 1 " +
                "WHERE `Player` = ?";
    }

    private final ConnectionManager connectionManager = ConnectionManager.DEFAULT;
    private final PlayerManager playerManager = PlayerManager.DEFAULT;
    private final UUID uuid;
    private final PlayerZQL main;
    
    /**
     * A Runnable which is run after player's joining (or during pre-login in non-Bungee mode), in order to load player's data from the database.
     * @param uuid The Player's UUID whose data is being loaded
     * @param main An instance of the main class
     */
    public LoadTask(UUID uuid, PlayerZQL main) {
        this.uuid = uuid;
        this.main = main;
    }
    
    @Override
    public void run() {
        boolean success = this.doLoad(null);
        if(!success)
        {
            this.kick();
        }
    }
    
    /**
     * Loads the data.
     * @param nbme Used by the plugin in non-Bungee mode.
     * @return if the progress was successful
     */
    public boolean doLoad(NonBungeeModePlayerListener nbme)
    {
        try {
            Connection c = connectionManager.getConnection("playersql");
            
            PreparedStatement select = c.prepareStatement(SELECT);
            select.setString(1, uuid.toString());

            ResultSet result = select.executeQuery();
            if (!result.next()) { // Check if the player exists in the database
                createPlayer(c);
                processData(uuid, PlayerManager.FLAG_EMPTY, nbme);
            } else if (result.getInt(2) == 0) { // Check if the player is offline
                setAsOnline(c);
                processData(uuid, result.getString(1), nbme);
            } else if (result.getLong(3) != 0 && minutesPastSince(result.getLong(3)) > 5) {
                // Check if it's more than 5 minutes since the player's last online time.
                // If yes, which means the previous server was stopped unexpectedly, then load the data forcibly.
                String data = result.getString(1);
                processData(uuid, data != null ? data : PlayerManager.FLAG_EMPTY, nbme);
            } else {
                result.close();
                select.close();
                connectionManager.release("playersql", c);
                return false;
            }
            result.close();
            select.close();
            connectionManager.release("playersql", c);
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    private void processData(UUID uuid, String data, NonBungeeModePlayerListener nbme)
    {
        playerManager.setState(uuid, State.JOIN_DONE);
        if(null == nbme)
        {
            playerManager.getDataMap().put(uuid, data);
        }
        else
        {
            nbme.putData(uuid, data);
        }
    }

    private void kick() {
        Bukkit.getScheduler().runTask(main, new Runnable()
        {
            @Override
            public void run()
            {
                Bukkit.getServer().getPlayer(uuid).kickPlayer(PlayerManager.MESSAGE_KICK);
            }
        });
    }

    private long minutesPastSince(long last) {
        return (System.currentTimeMillis() - last) / 60000;
    }

    private void setAsOnline(Connection c) {
        try {
            PreparedStatement update = c.prepareStatement(UPDATE);
            update.setString(1, uuid.toString());
            update.executeUpdate();
            update.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void createPlayer(Connection c) {
        try {
            PreparedStatement insert = c.prepareStatement(INSERT);
            insert.setString(1, uuid.toString());
            insert.setLong(2, System.currentTimeMillis());
            insert.executeUpdate();
            insert.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

}
