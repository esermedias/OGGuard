package wtf.esuh.ogguard;

import wtf.esuh.ogguard.api.GuardApi;
import wtf.esuh.ogguard.platform.GuardListener;
import wtf.esuh.ogguard.platform.VisibilityService;
import wtf.esuh.ogguard.visibility.nms.NmsBridge;
import wtf.esuh.ogguard.visibility.nms.ViewerSession;
import wtf.esuh.ogguard.visibility.policy.ContainerRule;
import wtf.esuh.ogguard.visibility.policy.ContainerRuleParser;
import wtf.esuh.ogguard.visibility.policy.ViewerState;
import wtf.esuh.ogguard.visibility.policy.VisibilityConfig;
import wtf.esuh.ogguard.visibility.policy.VisibilityConfigParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.logging.Level;

/** Plugin entry point: loads the config, wires the visibility service and handles /ogguard. */
public final class OGGuardPlugin extends JavaPlugin {

    private VisibilityService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        VisibilityConfig config;
        ContainerRule containers;
        try {
            config = loadVisibilityConfig();
            containers = loadContainerRule();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Invalid packet-visibility config; disabling OGGuard.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        NmsBridge nms;
        try {
            nms = new NmsBridge();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getLogger().log(Level.SEVERE, "Server internals do not match the verified 1.21.11 layout;"
                    + " packet visibility cannot run. Disabling OGGuard.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        long period = getConfig().getLong("packet-visibility.update-period-ticks", 5L);
        service = new VisibilityService(this, nms, config, containers, period);
        getServer().getPluginManager().registerEvents(new GuardListener(service), this);
        getServer().getServicesManager().register(GuardApi.class, service, this, ServicePriority.Normal);
        for (Player player : getServer().getOnlinePlayers()) {
            player.getScheduler().run(this, t -> service.attach(player), null); // plugin reload while online
        }
        getLogger().info("Packet visibility active: " + summary(config) + "; " + summary(containers));
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.detachAll();
            getServer().getServicesManager().unregisterAll(this);
        }
    }

    private VisibilityConfig loadVisibilityConfig() {
        reloadConfig();
        ConfigurationSection section = getConfig().getConfigurationSection("packet-visibility");
        Map<String, Object> values = section == null ? Map.of() : section.getValues(false);
        return VisibilityConfigParser.parse(values);
    }

    private ContainerRule loadContainerRule() {
        ConfigurationSection section = getConfig().getConfigurationSection("packet-visibility.container-concealment");
        Map<String, Object> values = section == null ? Map.of() : section.getValues(false);
        return ContainerRuleParser.parse(values);
    }

    private static String summary(ContainerRule r) {
        return "containers: enabled=" + r.enabled() + ", maxDistance=" + r.maxDistance()
                + ", checkInterval=" + r.checkIntervalTicks() + ", types=" + r.blockEntityTypes().size();
    }

    private static String summary(VisibilityConfig c) {
        return "enabled=" + c.enabled() + ", boundaryY=" + c.boundaryY() + ", revealEnterY=" + c.revealEnterY()
                + ", concealExitY=" + c.concealExitY() + ", radius=" + c.revealRadiusChunks()
                + ", replacement=" + c.replacement() + ", protectAll=" + c.protectAllBelowBoundary()
                + ", regions=" + c.protectedRegions().size() + ", revealZones=" + c.revealZones().size();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (service == null) {
            sender.sendMessage("OGGuard is not running.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                try {
                    VisibilityConfig config = loadVisibilityConfig();
                    ContainerRule containers = loadContainerRule();
                    service.reload(config, containers);
                    sender.sendMessage("Packet visibility reloaded: " + summary(config) + "; " + summary(containers));
                } catch (RuntimeException e) {
                    sender.sendMessage("Config invalid, keeping previous settings: " + e.getMessage());
                }
            }
            case "status" -> {
                sender.sendMessage("Packet visibility: " + summary(service.policy().config())
                        + "; " + summary(service.policy().containerRule())
                        + ", api regions=" + service.protectedRegions().size()
                        + ", sessions=" + service.sessionCount());
                if (sender instanceof Player player) {
                    ViewerSession s = service.session(player.getUniqueId());
                    if (s != null) {
                        ViewerState v = s.state();
                        sender.sendMessage("You: revealed=" + v.revealed() + ", bypass=" + v.bypass()
                                + ", client chunks=" + s.ledger().size()
                                + ", visible containers=" + s.visibleContainers().positions().size()
                                + ", withheld container chunks=" + s.withheldContainers().snapshot().size());
                    }
                }
            }
            case "selftest" -> {
                org.bukkit.World world = sender instanceof Player pl ? pl.getWorld() : getServer().getWorlds().get(0);
                org.bukkit.Location at = sender instanceof Player pl ? pl.getLocation() : world.getSpawnLocation();
                service.selfTest(world, at.getBlockX() >> 4, at.getBlockZ() >> 4, sender::sendMessage);
            }
            default -> sender.sendMessage("Usage: /" + label + " <status|reload|selftest>");
        }
        return true;
    }
}
