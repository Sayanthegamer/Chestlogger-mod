package com.chestlogger.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Fabric ChestLogger Commands & Help Parity Tests")
class FabricChestLoggerCommandsTest {

    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void initMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        dispatcher = new CommandDispatcher<>();
        ChestLoggerCommands.registerCommands(dispatcher);
    }

    @Test
    @DisplayName("Root /chestlog command and /cl alias are registered")
    void testCommandTreeRegistration() {
        CommandNode<CommandSourceStack> chestlogNode = dispatcher.getRoot().getChild("chestlog");
        assertThat(chestlogNode).isNotNull();

        CommandNode<CommandSourceStack> clNode = dispatcher.getRoot().getChild("cl");
        assertThat(clNode).isNotNull();
        assertThat(clNode.getRedirect()).isSameAs(chestlogNode);
    }

    @Test
    @DisplayName("Root /chestlog node has default executor for non-op player help and op inspect")
    void testRootCommandHasExecutor() {
        CommandNode<CommandSourceStack> chestlogNode = dispatcher.getRoot().getChild("chestlog");
        assertThat(chestlogNode).isNotNull();
        assertThat(chestlogNode.getCommand())
                .as("Root /chestlog command must have an execution handler")
                .isNotNull();
    }

    @Test
    @DisplayName("Subcommand /chestlog help is registered and accessible")
    void testHelpSubcommandRegistration() {
        CommandNode<CommandSourceStack> chestlogNode = dispatcher.getRoot().getChild("chestlog");
        assertThat(chestlogNode).isNotNull();

        CommandNode<CommandSourceStack> helpNode = chestlogNode.getChild("help");
        assertThat(helpNode)
                .as("Subcommand /chestlog help must be registered")
                .isNotNull();
        assertThat(helpNode.getCommand()).isNotNull();
    }

    @Test
    @DisplayName("Player-accessible subcommands are registered as direct children of /chestlog")
    void testPlayerSubcommandsRegistered() {
        CommandNode<CommandSourceStack> chestlogNode = dispatcher.getRoot().getChild("chestlog");
        assertThat(chestlogNode).isNotNull();

        assertThat(chestlogNode.getChild("claim")).isNotNull();
        assertThat(chestlogNode.getChild("unclaim")).isNotNull();
        assertThat(chestlogNode.getChild("transfer")).isNotNull();
        assertThat(chestlogNode.getChild("trust")).isNotNull();
        assertThat(chestlogNode.getChild("untrust")).isNotNull();
        assertThat(chestlogNode.getChild("trustlist")).isNotNull();
        assertThat(chestlogNode.getChild("trace")).isNotNull();
    }

    @Test
    @DisplayName("Admin-only subcommands are registered under /chestlog")
    void testAdminSubcommandsRegistered() {
        CommandNode<CommandSourceStack> chestlogNode = dispatcher.getRoot().getChild("chestlog");
        assertThat(chestlogNode).isNotNull();

        assertThat(chestlogNode.getChild("inspect")).isNotNull();
        assertThat(chestlogNode.getChild("i")).isNotNull();
        assertThat(chestlogNode.getChild("wand")).isNotNull();
        assertThat(chestlogNode.getChild("rollback")).isNotNull();
        assertThat(chestlogNode.getChild("stats")).isNotNull();
        assertThat(chestlogNode.getChild("purge")).isNotNull();
        assertThat(chestlogNode.getChild("config")).isNotNull();
        assertThat(chestlogNode.getChild("settings")).isNotNull();
    }
}
