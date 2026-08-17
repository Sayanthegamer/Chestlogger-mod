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

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Paper Trust Command & Tab Completion Tests")
class PaperTrustCommandTest {

    @TempDir
    Path tempDir;

    private TrustManager trustManager;
    private PaperCommandExecutor executor;
    private Plugin mockPlugin;
    private Command mockCommand;

    @BeforeEach
    void setUp() {
        trustManager = new TrustManager(tempDir.resolve("trust_data.json"));
        mockPlugin = createMockPlugin();
        mockCommand = createMockCommand("chestlog");

        TransactionEventQueue eventQueue = new TransactionEventQueue(100);
        PersistentIndexManager indexManager = new PersistentIndexManager(tempDir.toFile());
        QueryEngine queryEngine = new QueryEngine(tempDir.toFile(), new com.chestlogger.storage.LZ4BlockCompressor(), indexManager, com.chestlogger.storage.StringTableDictionary::new);
        PaperRollbackExecutor rollbackExecutor = new PaperRollbackExecutor(eventQueue);
        InspectModeManager inspectModeManager = new InspectModeManager();

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
    @DisplayName("Player successfully trusts another player")
    void testTrustCommandSuccess() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"trust", "Bob"});
        assertThat(result).isTrue();

        UUID bobUuid = resolveOfflineUuid("Bob");
        assertThat(trustManager.isTrusted(alice.uuid, bobUuid)).isTrue();
        assertThat(alice.messages).anyMatch(msg -> msg.contains("Successfully trusted Bob"));
    }

    @Test
    @DisplayName("Self-trust is rejected with error message")
    void testTrustSelfRejection() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"trust", "Alice"});
        assertThat(result).isTrue();

        assertThat(alice.messages).anyMatch(msg -> msg.contains("You cannot trust yourself"));
    }

    @Test
    @DisplayName("Trusting an already trusted player informs sender")
    void testTrustAlreadyTrusted() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();
        UUID bobUuid = resolveOfflineUuid("Bob");
        trustManager.trust(alice.uuid, bobUuid);

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"trust", "Bob"});
        assertThat(result).isTrue();

        assertThat(alice.messages).anyMatch(msg -> msg.contains("already in your trust list"));
    }

    @Test
    @DisplayName("Trust command without player argument shows usage")
    void testTrustMissingArgument() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"trust"});
        assertThat(result).isTrue();

        assertThat(alice.messages).anyMatch(msg -> msg.contains("Usage: /chestlog trust <player>"));
    }

    @Test
    @DisplayName("Player successfully untrusts an existing trusted player")
    void testUntrustCommandSuccess() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();
        UUID bobUuid = resolveOfflineUuid("Bob");
        trustManager.trust(alice.uuid, bobUuid);
        assertThat(trustManager.isTrusted(alice.uuid, bobUuid)).isTrue();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"untrust", "Bob"});
        assertThat(result).isTrue();

        assertThat(trustManager.isTrusted(alice.uuid, bobUuid)).isFalse();
        assertThat(alice.messages).anyMatch(msg -> msg.contains("Successfully untrusted Bob"));
    }

    @Test
    @DisplayName("Untrusting an untrusted player informs sender")
    void testUntrustNotTrusted() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"untrust", "Charlie"});
        assertThat(result).isTrue();

        assertThat(alice.messages).anyMatch(msg -> msg.contains("not in your trust list"));
    }

    @Test
    @DisplayName("Trustlist displays empty notification when no players are trusted")
    void testTrustListEmpty() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"trustlist"});
        assertThat(result).isTrue();

        assertThat(alice.messages).anyMatch(msg -> msg.contains("You have not trusted any players yet"));
    }

    @Test
    @DisplayName("Trustlist formats and displays all trusted players")
    void testTrustListPopulated() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();
        UUID bobUuid = resolveOfflineUuid("Bob");
        UUID charlieUuid = resolveOfflineUuid("Charlie");
        trustManager.trust(alice.uuid, bobUuid);
        trustManager.trust(alice.uuid, charlieUuid);

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"trustlist"});
        assertThat(result).isTrue();

        assertThat(alice.messages).anyMatch(msg -> msg.contains("Trusted Players (2)"));
        assertThat(alice.messages).anyMatch(msg -> msg.contains(bobUuid.toString()) || msg.contains("Bob"));
    }

    @Test
    @DisplayName("Command rejected when sender lacks permission")
    void testPermissionDenied() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of()); // No permissions
        Player aliceProxy = alice.createProxy();

        boolean result = executor.onCommand(aliceProxy, mockCommand, "chestlog", new String[]{"trust", "Bob"});
        assertThat(result).isTrue();

        assertThat(alice.messages).anyMatch(msg -> msg.contains("You do not have permission"));
        assertThat(trustManager.isTrusted(alice.uuid, resolveOfflineUuid("Bob"))).isFalse();
    }

    @Test
    @DisplayName("Console sender is rejected for player-only trust commands")
    void testConsoleSenderRejection() {
        TestConsole console = new TestConsole(Set.of("chestlogger.admin"));
        CommandSender consoleProxy = console.createProxy();

        boolean result = executor.onCommand(consoleProxy, mockCommand, "chestlog", new String[]{"trust", "Bob"});
        assertThat(result).isTrue();

        assertThat(console.messages).anyMatch(msg -> msg.contains("only be executed by in-game players"));
    }

    @Test
    @DisplayName("Tab completion suggests trust, untrust, and trustlist subcommands")
    void testTabCompletionSubcommands() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();

        List<String> completions = executor.onTabComplete(aliceProxy, mockCommand, "chestlog", new String[]{""});
        assertThat(completions).contains("trust", "untrust", "trustlist", "inspect", "rollback", "stats");

        List<String> trCompletions = executor.onTabComplete(aliceProxy, mockCommand, "chestlog", new String[]{"tr"});
        assertThat(trCompletions).contains("trust", "trace");
    }

    @Test
    @DisplayName("Tab completion for untrust suggests currently trusted players")
    void testTabCompletionUntrust() {
        TestPlayer alice = new TestPlayer("Alice", UUID.randomUUID(), Set.of("chestlogger.trust"));
        Player aliceProxy = alice.createProxy();
        UUID bobUuid = resolveOfflineUuid("Bob");
        trustManager.trust(alice.uuid, bobUuid);

        List<String> completions = executor.onTabComplete(aliceProxy, mockCommand, "chestlog", new String[]{"untrust", ""});
        assertThat(completions).isNotEmpty();
    }

    private static UUID resolveOfflineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name.toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private static final class TestConsole {
        final Set<String> permissions;
        final List<String> messages = new ArrayList<>();

        TestConsole(Set<String> permissions) {
            this.permissions = permissions;
        }

        CommandSender createProxy() {
            return (CommandSender) Proxy.newProxyInstance(
                    CommandSender.class.getClassLoader(),
                    new Class<?>[]{CommandSender.class},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if ("getName".equals(m)) return "CONSOLE";
                        if ("hasPermission".equals(m)) {
                            String perm = (String) args[0];
                            return permissions.contains(perm) || permissions.contains("*");
                        }
                        if ("sendMessage".equals(m)) {
                            if (args != null && args.length > 0 && args[0] != null) {
                                messages.add(args[0].toString());
                            }
                            return null;
                        }
                        if ("equals".equals(m)) return proxy == args[0];
                        if ("toString".equals(m)) return "TestConsole";
                        Class<?> r = method.getReturnType();
                        if (r == boolean.class) return false;
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
