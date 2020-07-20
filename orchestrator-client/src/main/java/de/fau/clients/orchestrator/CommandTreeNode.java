package de.fau.clients.orchestrator;

import de.fau.clients.orchestrator.nodes.TypeDefLut;
import de.fau.clients.orchestrator.tasks.CommandTask;
import de.fau.clients.orchestrator.tasks.CommandTaskModel;
import java.util.UUID;
import javax.swing.tree.DefaultMutableTreeNode;
import sila_java.library.core.models.Feature.Command;

@SuppressWarnings("serial")
public class CommandTreeNode extends DefaultMutableTreeNode {

    private final CommandTaskModel commandModel;

    public CommandTreeNode(final CommandTaskModel commandModel) {
        super();
        this.commandModel = commandModel;
    }

    public CommandTreeNode(
            final UUID serverUuid,
            final TypeDefLut typeDefs,
            final Command command) {
        this(new CommandTaskModel(serverUuid, typeDefs, command));
    }

    /**
     * Creates a new table entry for the task queue.
     *
     * @return The command used for the task queue table.
     */
    public CommandTask createTableEntry() {
        return new CommandTask(commandModel);
    }

    @Override
    public String toString() {
        return commandModel.getCommand().getDisplayName();
    }
}
