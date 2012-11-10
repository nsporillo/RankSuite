package net.krinsoft.ranksuite;

import net.krinsoft.ranksuite.commands.CommandHandler;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * @author krinsdeath
 */
public class RankCore extends JavaPlugin {

    private final static Pattern LAST = Pattern.compile("\\[old\\]");
    private final static Pattern NEXT = Pattern.compile("\\[new\\]");
    private final static Pattern USER = Pattern.compile("\\[user\\]");

    private FileConfiguration db;

    private LinkedHashMap<String, Integer> leaders = new LinkedHashMap<String, Integer>();

    private CommandHandler commands;

    private Map<String, Rank> ranks = new HashMap<String, Rank>();
    private Map<String, RankedPlayer> players = new HashMap<String, RankedPlayer>();

    private boolean debug = false;
    private List<String> promotion = new ArrayList<String>();

    @Override
    public void onEnable() {
        // register the player join event
        RankListener listener = new RankListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        // fetch the config file and default values
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveDefaultConfig();
        }
        this.debug = getConfig().getBoolean("plugin.debug", false);
        this.promotion = getConfig().getStringList("promote");

        // build the ranks
        for (String rank : getConfig().getConfigurationSection("ranks").getKeys(false)) {
            ConfigurationSection section = getConfig().getConfigurationSection("ranks." + rank);
            Rank rank1 = new Rank(rank, section.getString("next_rank"), section.getInt("time_played"), section.getString("message"));
            ranks.put(rank, rank1);
        }

        // build a new rank database or import AutoRank's
        File file = new File("plugins/AutoRank/data.yml");
        if (file.exists()) {
            getLogger().info("Using AutoRank's data.yml for current rankings...");
            if (file.renameTo(new File(getDataFolder(), "players.db"))) {
                getLogger().info("Rankings imported.");
            }
        }
        getDB();

        // create the leaderboard
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                buildLeaderboard();
            }
        }, 1L, 36000L);

        // build the command handler
        commands = new CommandHandler(this);

        // register a scheduled task to update players dynamically
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                for (String name : players.keySet()) {
                    promote(name);
                }
                saveDB();
            }
        }, 1L, 6000L);
    }

    @Override
    public void onDisable() {
        saveDB();
        getServer().getScheduler().cancelTasks(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        commands.runCommand(sender, label, args);
        return true;
    }

    public FileConfiguration getDB() {
        if (db == null) {
            db = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "players.db"));
        }
        return db;
    }

    public void saveDB() {
        try {
            db.save(new File(getDataFolder(), "players.db"));
        } catch (IOException e) {
            getLogger().warning("An error occurred while saving the players database.");
        }
    }

    public void debug(String message) {
        if (debug) {
            String msg = "[Debug] " + message;
            getLogger().info(msg);
        }
    }

    private void buildLeaderboard() {
        debug("Initializing leaderboards...");
        Map<String, Integer> leaders = new LinkedHashMap<String, Integer>();
        for (String key : getDB().getKeys(false)) {
            leaders.put(key, getDB().getInt(key));
        }
        LinkedList<Map.Entry<String, Integer>> list = new LinkedList<Map.Entry<String, Integer>>(leaders.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2) {
                return o1.getValue().compareTo(o2.getValue());
            }
        });
        this.leaders.clear();
        for (Map.Entry<String, Integer> entry : list) {
            this.leaders.put(entry.getKey(), entry.getValue());
        }
        debug("Leaderboards built.");
    }

    /**
     * Inefficient and horrifying, don't use this ever.
     * @param page The page to begin searching on
     * @return A map of the leaders matching the specified page
     */
    public LinkedHashMap<String, Integer> getLeaders(int page) {
        int search = page * 10 + 10;
        if (leaders.size() / 10 < page) {
            page = 0;
        }
        if (search > leaders.size()) {
            search = leaders.size();
        }
        LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
        String[] array = leaders.keySet().toArray(new String[leaders.size()]);

        for (int i = page * 10; i < search; i++) {
            map.put(array[i], leaders.get(array[i]));
        }
        return map;
    }

    public RankedPlayer getPlayer(String name) {
        RankedPlayer player = players.get(name);
        if (player == null) {
            player = promote(name);
        }
        return player;
    }

    /**
     * Gets the specified rank by its name
     * @param name The name of the rank we're fetching
     * @return The rank object
     */
    public Rank getRank(String name) {
        return ranks.get(name);
    }

    /**
     * Fetches the highest possible rank according to the specified minutes
     * @param mins The number of minutes
     * @return The rank that was fetched.
     */
    public Rank getRank(int mins) {
        if (mins < 0) { mins = 0; }
        int highest = 0;
        Rank match = null;
        for (Rank r : ranks.values()) {
            if (r.getMinutesRequired() <= mins && r.getMinutesRequired() >= highest) {
                highest = r.getMinutesRequired();
                match = r;
            }
        }
        return match;
    }

    public void login(final String name) {
        getServer().getScheduler().scheduleAsyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                promote(name);
            }
        }, 100L);
    }

    /**
     * Checks whether the specified player is qualified for a promotion, and promotes them if they are
     * @param name The name of the player
     */
    public RankedPlayer promote(final String name) {
        final Player promoted = getServer().getPlayer(name);
        RankedPlayer player = players.get(name);
        if (player == null) {
            int minutes = getDB().getInt(name, 0);
            Rank rank = getRank(minutes);
            debug(name + " determined to be " + rank.getName() + " with " + minutes + " minute(s) played.");
            if (promoted == null) {
                getLogger().warning("Something went wrong... a fetched player was null!");
                return null;
            }
            player = new RankedPlayer(this, name, rank, minutes, System.currentTimeMillis(), promoted.hasPermission("ranksuite.exempt"));
        }
        if (player.addTime()) {
            // player is qualified for a promotion
            Rank last = player.getRank();
            Rank next = getRank(player.getRank().getNextRank());
            for (String cmd : this.promotion) {
                cmd = LAST.matcher(cmd).replaceAll(last.getName());
                cmd = NEXT.matcher(cmd).replaceAll(next.getName());
                cmd = USER.matcher(cmd).replaceAll(name);
                getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
            }
            if (next.getPromotionMessage() != null) {
                for (String msg : next.getPromotionMessage().split("\n")) {
                    promoted.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                }
            }
            player.setRank(next);
        }
        players.put(name, player);
        return player;
    }

    public void retire(String name) {
        RankedPlayer player = players.remove(name);
        player.addTime();
    }

    public void checkRank(CommandSender sender, Player player) {
        if (sender.equals(player) || sender.hasPermission("ranksuite.check.other") || !(sender instanceof Player)) {
            RankedPlayer p = players.get(player.getName());
            if (p == null) {
                sender.sendMessage(ChatColor.RED + "Something went wrong.");
                return;
            }
            if (p.addTime()) {
                // user qualified for a promotion
                promote(player.getName());
            }
            String name = (sender.equals(player) ? "You" : player.getName());
            sender.sendMessage(ChatColor.AQUA + name + ChatColor.GREEN + (sender.equals(player) ? " are" : " is") + " in the rank: " + ChatColor.AQUA + p.getRank().getName());
            sender.sendMessage(ChatColor.AQUA + name + ChatColor.GREEN + (sender.equals(player) ? " have" : " has") + " played for " + ChatColor.AQUA + p.getTimePlayed() + " minute(s)" + ChatColor.GREEN + ".");
            if (p.getRank().getNextRank() != null) {
                Rank next = getRank(p.getRank().getNextRank());
                if (next != null) {
                    sender.sendMessage(ChatColor.AQUA + name + ChatColor.GREEN + " will rank up to " + ChatColor.AQUA + next.getName() + ChatColor.GREEN + " in " + ChatColor.AQUA + (next.getMinutesRequired() - p.getTimePlayed()) + " minute(s)" + ChatColor.GREEN + ".");
                }
            }
        }
    }

}