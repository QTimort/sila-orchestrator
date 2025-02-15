package de.fau.clients.orchestrator.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.fau.clients.orchestrator.Presentable;
import de.fau.clients.orchestrator.ctx.FeatureContext;
import de.fau.clients.orchestrator.ctx.PropertyContext;
import de.fau.clients.orchestrator.nodes.NodeFactory;
import de.fau.clients.orchestrator.nodes.SilaNode;
import de.fau.clients.orchestrator.utils.IconProvider;
import de.fau.clients.orchestrator.utils.SilaBasicTypeUtils;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.tree.DefaultMutableTreeNode;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import sila_java.library.core.models.Feature;
import sila_java.library.core.sila.errors.SiLAErrorException;
import sila_java.library.manager.ServerManager;
import sila_java.library.manager.executor.ExecutableServerCall;
import sila_java.library.manager.models.SiLACall;

/**
 * Representation of a SiLA Property in the Feature tree.
 */
@Slf4j
@SuppressWarnings("serial")
public class PropertyTreeNode extends DefaultMutableTreeNode implements Presentable {

    /**
     * Index to place and update the contents of the panel.
     */
    private static final int CONTENT_COMPONENT_IDX = 0;
    private static final int MAX_SERVER_RESPONSE_TIME_IN_SEC = 3;
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private final PropertyContext propCtx;
    private JPanel panel;
    private JButton refreshBtn;
    private SilaNode node;
    private String lastResult = "";

    /**
     * Constructor.
     *
     * @param propCtx The property context.
     */
    public PropertyTreeNode(@NonNull final PropertyContext propCtx) {
        this.propCtx = propCtx;
    }

    /**
     * Gets a <code>JPanel</code> populated with widgets viewing the current SiLA Property.
     *
     * @return A <code>JPanel</code> representing the SiLA Property.
     */
    @Override
    public JPanel getPresenter() {
        if (panel == null) {
            panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(this.propCtx.getProperty().getDisplayName()),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            refreshBtn = new JButton("Refresh", IconProvider.REFRESH.getIcon());
            refreshBtn.addActionListener((ActionEvent evt) -> {
                refreshBtnActionPerformed();
            });
        } else {
            panel.removeAll();
        }

        if (node != null) {
            panel.add(node.getComponent(), CONTENT_COMPONENT_IDX);
        } else {
            // node is null -> show exception message
            panel.add(new JLabel(lastResult), CONTENT_COMPONENT_IDX);
        }
        panel.add(Box.createVerticalStrut(10));
        panel.add(refreshBtn);
        return panel;
    }

    /**
     * Request the current SiLA Property data form the server and updates the internal
     * <code>SilaNode</code>.
     */
    public void requestPropertyData() {
        final FeatureContext featCtx = propCtx.getFeatureCtx();
        final Feature.Property property = propCtx.getProperty();
        final SiLACall.Type callType = property.getObservable().equalsIgnoreCase("yes")
                ? SiLACall.Type.OBSERVABLE_PROPERTY
                : SiLACall.Type.UNOBSERVABLE_PROPERTY;
        final SiLACall.Builder callBuilder = new SiLACall.Builder(
                featCtx.getServerUuid(),
                featCtx.getFeatureId(),
                property.getIdentifier(),
                callType
        );

        boolean wasSuccessful = false;
        try {
            final ExecutableServerCall executableServerCall = ExecutableServerCall.newBuilder(callBuilder.build()).build();
            final Future<String> futureCallResult = ServerManager.getInstance().getServerCallManager().runAsync(executableServerCall);
            lastResult = futureCallResult.get(MAX_SERVER_RESPONSE_TIME_IN_SEC, TimeUnit.SECONDS);
            wasSuccessful = true;
        } catch (final TimeoutException ex) {
            final String msg = "Timeout: Server did not responde within " + MAX_SERVER_RESPONSE_TIME_IN_SEC + " sec.";
            log.error(msg);
            lastResult = msg;
        } catch (final ExecutionException ex) {
            final String msg;
            if (ex.getCause() instanceof SiLAErrorException) {
                msg = SilaBasicTypeUtils.formatSilaErrorToMsgString(((SiLAErrorException) ex.getCause()).getSiLAError());
            } else {
                msg = ex.getMessage();
            }
            log.error(msg);
            lastResult = msg;
        } catch (final Exception ex) {
            log.error(ex.getMessage());
            lastResult = ex.getMessage();
        }

        if (!wasSuccessful) {
            node = null;
            return;
        }

        final JsonNode rootNode;
        try {
            rootNode = jsonMapper.readTree(lastResult);
        } catch (IOException ex) {
            log.error(ex.getMessage());
            return;
        }

        node = NodeFactory.createFromJson(
                featCtx,
                property.getDataType(),
                rootNode.get(property.getIdentifier()),
                false);
    }

    @Override
    public String toString() {
        return this.propCtx.getProperty().getDisplayName();
    }

    /**
     * Requests the current state of the SiLA Property form the server and updates the view of the
     * GUI components. The internal panel has to be constructed before using this function.
     */
    private void refreshBtnActionPerformed() {
        requestPropertyData();
        panel.remove(CONTENT_COMPONENT_IDX);
        if (node != null) {
            panel.add(node.getComponent(), CONTENT_COMPONENT_IDX);
        } else {
            panel.add(new JLabel(lastResult), CONTENT_COMPONENT_IDX);
        }
        panel.revalidate();
        panel.repaint();
    }

    public SilaNode getCurrentSilaNode() {
        requestPropertyData();
        return node;
    }
}
