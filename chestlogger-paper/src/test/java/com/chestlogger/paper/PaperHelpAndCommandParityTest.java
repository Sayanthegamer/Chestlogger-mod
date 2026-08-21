package com.chestlogger.paper;

import com.chestlogger.event.TransactionEventQueue;
import com.chestlogger.index.PersistentIndexManager;
import com.chestlogger.inspect.InspectModeManager;
import com.chestlogger.query.QueryEngine;
import com.chestlogger.security.TrustManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Paper Help and Command Parity Tests")
class PaperHelpAndCommandParityTest {

    @TempDir
    Path tempDir;

    private TrustManager trustManager;
    private InspectModeManager inspectModeManager;
    private PaperCommandExecutor executor;
    private Plugin mockPlugin;
    private Command mockCommand;

    @BeforeEach
    void setUp() {
        trustManager = new TrustManager(tempDir.resolve("trust_data.json"));
        inspectModeManager = new InspectModeManager();
        mockPlugin = createMockPlugin();
        mockCommand = createMockCommand("chestlog");

        TransactionEventQueue eventQueue = new TransactionEventQueue(100);
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir.toFile());
        QueryEngine queryEngine = new QueryEngine(tempDir.toFile(), new com.chestlogger.storage.LZ4BlockCompressor(), indexManager, com.chestlogger.storage.StringTableDictionary::new);
        PaperRollbackExecutor rollbackExecutor = new PaperRollbackExecutor(eventQueue);

        executor = new PaperCommandExecutor(
                mockPlugin,
                queryEngine,
                indexManager,
                eventQueue,
                rollbackExecutor,
                null,
                inspectModeManager,
                trustManager
        );
    }

    @Test
    @DisplayName("Non-op player executing /chestlog with no args receives player help menu")
    void testNonOpChestlogCommandShowsHelp() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.claim", "chestlogger.trust", "chestlogger.trace"));
        Player aliceProxy = alice.createProxy();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{});
        assertThat(result).isTrue();

        assertThat(alice.messages).anyMatch(msg -> msg.contains("ChestLogger Commands"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains("/chestlog claim"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains("/chestlog unclaim"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains("/chestlog transfer"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains("/chestlog trust"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains("/chestlog untrust"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains("/chestlog trustlist"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains("/chestlog trace"));
        assertThat(inspectModeManager.isInspectActive(alice.uuid)).isFalse();
    }

    @Test
    @DisplayName("Executing /chestlog help explicitly displays help menu for players and admins")
    void testExplicitHelpCommand() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.claim"));
        Player aliceProxy = alice.createProxy();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"help"});
        assertThat(result).isTrue();
        assertThat(alice.messages).anyMatch(msg -> msg.contains("ChestLogger Commands"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains("/chestlog claim"));

        TestPlayer admin = new TestPlayer("AdminBob", UUID.randomUUID(), Set.of("chestlogger.admin"));
        Player adminProxy = admin.createProxy();

        boolean adminResult = executor.onCommand(adminProxy, mockCommand, "chestlog", new String[]{"help"});
        assertThat(adminResult).isTrue();
        assertThat(admin.messages).anyMatch(msg -> msg.contains("ChestLogger Commands"));
        assertThat(admin.messages).anyMatch(msg -> msg.contains("/chestlog [i|inspect]"));
    }

    @Test
    @DisplayName("Operator executing /chestlog with no args toggles inspect mode")
    void testOpChestlogCommandTogglesInspect() {
        TestPlayer admin = new TestPlayer("AdminBob", UUID.randomUUID(), Set.of("chestlogger.inspect"));
        Player adminProxy = admin.createProxy();

        boolean result = executor.onCommand(adminProxy, mockCommand, "chestlog", new String[]{});
        assertThat(result).isTrue();

        assertThat(inspectModeManager.isInspectActive(admin.uuid)).isTrue();
        assertThat(admin.messages).anyMatch(msg -> msg.contains("Inspect mode enabled"));

        // Toggle off
        executor.onCommand(adminProxy, mockCommand, "chestlog", new String[]{});
        assertThat(inspectModeManager.isInspectActive(admin.uuid)).isFalse();
        assertThat(admin.messages).anyMatch(msg -> msg.contains("Inspect mode disabled"));
    }

    @Test
    @DisplayName("Tab completion includes help subcommand for players and admins")
    void testTabCompletionIncludesHelp() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.claim"));
        Player aliceProxy = alice.createProxy();

        List<String> completions = executor.onTabComplete(aliceProxy, mockCommand, "chestlog", new String[]{""});
        assertThat(completions).contains("help", "claim", "unclaim", "transfer", "trust", "untrust", "trustlist", "trace");

        List<String> hCompletions = executor.onTabComplete(aliceProxy, mockCommand, "chestlog", new String[]{"he"});
        assertThat(hCompletions).containsExactly("help");
    }

    private static final class TestPlayer {
        final String name;
        final UUID uuid;
        final Set<String> permissions;
        final List<String> messages = new ArrayList<>();

        TestPlayer(String name, UUID uuid, Set<String> permissions) {
            this.name = name;
            this.uuid = uuid;
            this.permissions = permissions;
        }

        Player createProxy() {
            return (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if ("getName".equals(m)) return name;
                        if ("getUniqueId".equals(m)) return uuid;
                        if ("hasPermission".equals(m)) {
                            String perm = (String) args[0];
                            return permissions.contains(perm) || permissions.contains("*");
                        }
                        if ("isPermissionSet".equals(m)) {
                            String perm = (String) args[0];
                            return permissions.contains(perm);
                        }
                        if ("sendMessage".equals(m)) {
                            if (args != null && args.length > 0 && args[0] != null) {
                                messages.add(args[0].toString());
                            }
                            return null;
                        }
                        if ("equals".equals(m)) return proxy == args[0];
                        if ("hashCode".equals(m)) return uuid.hashCode();
                        if ("toString".equals(m)) return "TestPlayer[" + name + "]";
                        Class<?> r = method.getReturnType();
                        if (r == boolean.class) return false;
                        if (r == int.class) return 0;
                        return null;
                    }
            );
        }
    }

    private Plugin createMockPlugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getLogger".equals(m)) return java.util.logging.Logger.getLogger("TestPlugin");
                    if ("getName".equals(m)) return "ChestLogger";
                    if ("getServer".equals(m)) return null;
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) return false;
                    return null;
                }
        );
    }

    private Command createMockCommand(String name) {
        return new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return false;
            }
        };
    }
}
