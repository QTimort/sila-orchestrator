package de.fau.clients.orchestrator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import de.fau.clients.orchestrator.cli.CommandlineArguments;
import de.fau.clients.orchestrator.cli.CommandlineControls;
import de.fau.clients.orchestrator.ctx.ConnectionManager;
import de.fau.clients.orchestrator.dnd.TaskExportTransferHandler;
import de.fau.clients.orchestrator.queue.Column;
import de.fau.clients.orchestrator.queue.TaskQueueData;
import de.fau.clients.orchestrator.queue.TaskQueueTable;
import de.fau.clients.orchestrator.tasks.DelayTask;
import de.fau.clients.orchestrator.tasks.ExecPolicy;
import de.fau.clients.orchestrator.tasks.LocalExecTask;
import de.fau.clients.orchestrator.tasks.QueueTask;
import de.fau.clients.orchestrator.tasks.TaskState;
import de.fau.clients.orchestrator.tree.CommandTreeNode;
import de.fau.clients.orchestrator.tree.ServerFeatureTree;
import de.fau.clients.orchestrator.tree.ServerTreeNode;
import de.fau.clients.orchestrator.utils.IconProvider;
import de.fau.clients.orchestrator.utils.SiloFileFilter;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.UUID;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.table.TableModel;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import static sila_java.library.core.encryption.EncryptionUtils.readCertificate;
import static sila_java.library.core.encryption.EncryptionUtils.writeCertificateToString;
import sila_java.library.manager.ServerAdditionException;

/**
 * The main GUI window and execution entry point of the client. It is advised to use the NetBeans
 * GUI designer to make changes on its layout.
 */
@Slf4j
@SuppressWarnings("serial")
public class OrchestratorGui extends javax.swing.JFrame {

    public static final String COPYRIGHT_NOTICE = "Copyright © 2020–2022 The sila-orchestrator Authors";
    private static final Image ICON_IMG = IconProvider.SILA_ORCHESTRATOR_16PX.getIcon().getImage();
    private static final String NO_ERROR_STR = "<No Error>";
    private static final String AUTHORS;
    private static final String LICENSE;
    private static final Properties GIT_PROPS = new Properties();
    private static ConnectionManager connectionManager;
    private final TaskQueueTable taskQueueTable = new TaskQueueTable();
    private final ServerFeatureTree serverFeatureTree = new ServerFeatureTree();
    private volatile boolean isQueueOnExecution = false;
    private boolean wasSaved = false;
    private String certificateStr = null;
    private Path outFilePath = null;
    private Thread currentlyExecutedTaskThread = null;

    static {
        final StringBuilder asb = new StringBuilder();
        try (final InputStream in = OrchestratorGui.class.getResourceAsStream("/AUTHORS");
             final BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.isEmpty()) {
                    continue;
                }
                asb.append("&emsp ").append(line).append("<br>");
            }
        } catch (final IOException ex) {
            throw new RuntimeException("Authors list cannot be loaded.", ex);
        }
        AUTHORS = asb.toString();

        final StringBuilder lsb = new StringBuilder();
        try (final InputStream in = OrchestratorGui.class.getResourceAsStream("/LICENSE");
             final BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                lsb.append(line).append('\n');
            }
        } catch (final IOException ex) {
            throw new RuntimeException("License text cannot be loaded.", ex);
        }
        LICENSE = lsb.toString();
    }

    private final String aboutInfo = "<html>"
            + "<p>Version: <b>" + GIT_PROPS.getProperty("git.build.version")
            + "-" + GIT_PROPS.getProperty("git.commit.id.abbrev") + "</b></p>"
            + "<p>"
            + "Git Commit: " + GIT_PROPS.getProperty("git.commit.id") + "<br>"
            + "Timestamp: " + GIT_PROPS.getProperty("git.commit.time") + "<br>"
            + "Repository: " + GIT_PROPS.getProperty("git.remote.origin.url") + "<br>"
            + "E-Mail: florian.bauer.dev@gmail.com<br>"
            + "License: Apache-2.0<br><br>"
            + "Authors: <br>"
            + AUTHORS
            + "</p></html>";

    private void addSpecificServer() {
        final String addr = serverAddressTextField.getText();
        int port;
        try {
            port = Integer.parseUnsignedInt(serverPortFormattedTextField.getText());
        } catch (final NumberFormatException ex) {
            serverAddErrorEditorPane.setText("Invalid port number. Possible range [1024..65535]");
            return;
        } catch (final Exception ex) {
            log.error(ex.getMessage());
            return;
        }

        final UUID serverUuid;
        try {
            if (certificateStr != null) {
                serverUuid = connectionManager.addServer(addr, port, certificateStr);
            } else {
                serverUuid = connectionManager.addServer(addr, port);
            }
        } catch (final Exception ex) {
            final String errMsg = ex.getMessage();
            final String warnStr = (errMsg == null || errMsg.isBlank()) ? "Unknown error." : errMsg;
            log.warn(warnStr);
            serverAddErrorEditorPane.setText(warnStr);
            return;
        }

        serverFeatureTree.putServerToTree(connectionManager.getServerCtx(serverUuid));
        serverFeatureTree.updateTreeView();
        serverAddErrorEditorPane.setText(NO_ERROR_STR);
        addServerDialog.setVisible(false);
        addServerDialog.dispose();
    }

    /**
     * Creates new form OrchestratorGui
     */
    public OrchestratorGui() {
        initComponents();
        initTaskQueueTable();
        initServerTree();
        scanServerBtn.grabFocus();
    }

    /**
     * This method is called from within the constructor to initialize the form. WARNING: Do NOT
     * modify this code. The content of this method is always regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        addServerDialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        addServerDialog.setTitle("Add Server");
        addServerDialog.setAlwaysOnTop(true);
        addServerDialog.setIconImage(ICON_IMG);
        addServerDialog.setModal(true);
        addServerDialog.setPreferredSize(new java.awt.Dimension(400, 400));
        addServerDialog.setLocationRelativeTo(null);
        java.awt.GridBagLayout addServerDialogLayout = new java.awt.GridBagLayout();
        addServerDialogLayout.columnWidths = new int[] {2};
        addServerDialogLayout.rowHeights = new int[] {8};
        addServerDialogLayout.columnWeights = new double[] {0.5, 0.5};
        addServerDialog.getContentPane().setLayout(addServerDialogLayout);

        serverAddressLabel.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        serverAddressLabel.setText("Server Address");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(5, 10, 0, 10);
        addServerDialog.getContentPane().add(serverAddressLabel, gridBagConstraints);

        serverAddressTextField.setToolTipText("e.g. 127.0.0.1, 192.168.0.2");
        serverAddressTextField.setPreferredSize(new java.awt.Dimension(128, 32));
        serverAddressTextField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                serverAddressTextFieldActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.PAGE_START;
        gridBagConstraints.insets = new java.awt.Insets(5, 10, 5, 10);
        addServerDialog.getContentPane().add(serverAddressTextField, gridBagConstraints);
        serverAddressTextField.getAccessibleContext().setAccessibleDescription("e.g. localhost, 192.168.0.2");
        serverAddressTextField.getAccessibleContext().setAccessibleParent(this);

        serverPortLabel.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        serverPortLabel.setText("Server Port");
        serverPortLabel.setHorizontalTextPosition(javax.swing.SwingConstants.LEFT);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(5, 10, 0, 10);
        addServerDialog.getContentPane().add(serverPortLabel, gridBagConstraints);

        serverPortFormattedTextField.setToolTipText("e.g. 50052, 55001 ");
        serverPortFormattedTextField.setPreferredSize(new java.awt.Dimension(128, 32));
        final NumberFormatter formatter = new NumberFormatter(new DecimalFormat("#0"));
        formatter.setMinimum(1024);
        formatter.setMaximum(65535);
        formatter.setAllowsInvalid(true);
        serverPortFormattedTextField.setFormatterFactory(new DefaultFormatterFactory(formatter));
        serverPortFormattedTextField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                serverPortFormattedTextFieldActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(5, 10, 5, 10);
        addServerDialog.getContentPane().add(serverPortFormattedTextField, gridBagConstraints);

        certificateLabel.setText("Certificate");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(5, 10, 0, 10);
        addServerDialog.getContentPane().add(certificateLabel, gridBagConstraints);

        certSerialNumberTextField.setText("<Serial Number>");
        certSerialNumberTextField.setToolTipText("The serial number of the certificate.");
        certSerialNumberTextField.setEnabled(false);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(5, 10, 5, 5);
        addServerDialog.getContentPane().add(certSerialNumberTextField, gridBagConstraints);

        openCertFileBtn.setText("Open Certificate");
        openCertFileBtn.setToolTipText("Choose a *.pem file containing the pulic key.");
        openCertFileBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openCertFileBtnActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(5, 5, 5, 10);
        addServerDialog.getContentPane().add(openCertFileBtn, gridBagConstraints);

        serverAddErrorScrollPane.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        serverAddErrorScrollPane.setEnabled(false);
        serverAddErrorScrollPane.setFocusable(false);
        serverAddErrorScrollPane.setMinimumSize(new java.awt.Dimension(64, 48));
        serverAddErrorScrollPane.setPreferredSize(new java.awt.Dimension(64, 52));

        serverAddErrorEditorPane.setEditable(false);
        serverAddErrorEditorPane.setText(NO_ERROR_STR);
        serverAddErrorEditorPane.setEnabled(false);
        serverAddErrorEditorPane.setFocusable(false);
        serverAddErrorEditorPane.setMargin(new java.awt.Insets(4, 4, 4, 24));
        serverAddErrorScrollPane.setViewportView(serverAddErrorEditorPane);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weighty = 0.5;
        gridBagConstraints.insets = new java.awt.Insets(5, 10, 5, 10);
        addServerDialog.getContentPane().add(serverAddErrorScrollPane, gridBagConstraints);

        serverDialogConnectBtn.setMnemonic('o');
        serverDialogConnectBtn.setText("Connect");
        serverDialogConnectBtn.setPreferredSize(new java.awt.Dimension(80, 42));
        serverDialogConnectBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                serverDialogConnectBtnActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 9;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(10, 10, 5, 5);
        addServerDialog.getContentPane().add(serverDialogConnectBtn, gridBagConstraints);

        serverDialogCancelBtn.setMnemonic('c');
        serverDialogCancelBtn.setText("Cancel");
        serverDialogCancelBtn.setPreferredSize(new java.awt.Dimension(80, 42));
        serverDialogCancelBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                serverDialogCancelBtnActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 9;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.insets = new java.awt.Insets(10, 5, 5, 10);
        addServerDialog.getContentPane().add(serverDialogCancelBtn, gridBagConstraints);

        addServerDialog.getAccessibleContext().setAccessibleParent(this);

        aboutDialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        aboutDialog.setTitle("About");
        aboutDialog.setAlwaysOnTop(true);
        aboutDialog.setIconImage(ICON_IMG);
        aboutDialog.setMinimumSize(new java.awt.Dimension(300, 256));
        aboutDialog.setModal(true);
        aboutDialog.setResizable(false);
        aboutDialog.setLocationRelativeTo(null);
        java.awt.GridBagLayout aboutDialogLayout = new java.awt.GridBagLayout();
        aboutDialogLayout.columnWidths = new int[] {1};
        aboutDialogLayout.rowHeights = new int[] {4};
        aboutDialog.getContentPane().setLayout(aboutDialogLayout);

        aboutLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        aboutLabel.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/sila-orchestrator-128px.png"))); // NOI18N
        aboutLabel.setText("<html><h1>sila-orchestrator</h1<p>" + COPYRIGHT_NOTICE + "</p></html>");
        aboutLabel.setAlignmentX(0.5F);
        aboutLabel.setIconTextGap(32);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(15, 15, 10, 15);
        aboutDialog.getContentPane().add(aboutLabel, gridBagConstraints);

        aboutInfoTextPane.setEditable(false);
        aboutInfoTextPane.setContentType("text/html"); // NOI18N
        aboutInfoTextPane.setText(aboutInfo);
        aboutInfoTextPane.setMargin(new java.awt.Insets(15, 15, 15, 15));
        aboutInfoTextPane.putClientProperty(javax.swing.JTextPane.HONOR_DISPLAY_PROPERTIES, true);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(15, 15, 15, 15);
        aboutDialog.getContentPane().add(aboutInfoTextPane, gridBagConstraints);

        viewLicenseBtn.setText("View License");
        viewLicenseBtn.setToolTipText("Shows the complete License text.");
        viewLicenseBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                viewLicenseBtnActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        aboutDialog.getContentPane().add(viewLicenseBtn, gridBagConstraints);

        aboutDialogCloseBtn.setMnemonic('c');
        aboutDialogCloseBtn.setText("Close");
        aboutDialogCloseBtn.setMargin(new java.awt.Insets(10, 15, 10, 15));
        aboutDialogCloseBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                aboutDialogCloseBtnActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(10, 15, 15, 15);
        aboutDialog.getContentPane().add(aboutDialogCloseBtn, gridBagConstraints);

        aboutDialog.getAccessibleContext().setAccessibleParent(this);

        licenseDialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        licenseDialog.setTitle("License");
        licenseDialog.setAlwaysOnTop(true);
        licenseDialog.setModal(true);
        licenseDialog.setPreferredSize(new java.awt.Dimension(650, 600));

        licenseTextArea.setEditable(false);
        licenseTextArea.setText(LICENSE);
        licenseScrollPane.setViewportView(licenseTextArea);

        licenseDialog.getContentPane().add(licenseScrollPane, java.awt.BorderLayout.CENTER);

        licenseDialog.getAccessibleContext().setAccessibleParent(aboutDialog);

        saveAsFileChooser.setDialogType(javax.swing.JFileChooser.SAVE_DIALOG);
        saveAsFileChooser.setDialogTitle("Save");
        saveAsFileChooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_AND_DIRECTORIES);

        openFileChooser.setFileFilter(new SiloFileFilter());

        certificateFileChooser.setDialogTitle("Open Certificate");
        certificateFileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("*.pem", "pem"));

        taskQueuePopupMenu.setFocusable(false);

        execRowEntryMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/execute-16px.png"))); // NOI18N
        execRowEntryMenuItem.setMnemonic('x');
        execRowEntryMenuItem.setText("Execute Entry");
        execRowEntryMenuItem.setToolTipText("Executes only this task entry alone.");
        execRowEntryMenuItem.setEnabled(false);
        execRowEntryMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                execRowEntryMenuItemActionPerformed(evt);
            }
        });
        taskQueuePopupMenu.add(execRowEntryMenuItem);

        removeTaskFromQueueMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/task-remove-16px.png"))); // NOI18N
        removeTaskFromQueueMenuItem.setMnemonic('r');
        removeTaskFromQueueMenuItem.setText("Remove Entry");
        removeTaskFromQueueMenuItem.setToolTipText("Removes this task entry from the queue.");
        removeTaskFromQueueMenuItem.setEnabled(false);
        removeTaskFromQueueMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                removeTaskFromQueue(evt);
            }
        });
        taskQueuePopupMenu.add(removeTaskFromQueueMenuItem);

        startQueueRunFromHereMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-run-from-16px.png"))); // NOI18N
        startQueueRunFromHereMenuItem.setMnemonic('f');
        startQueueRunFromHereMenuItem.setText("Run from Here");
        startQueueRunFromHereMenuItem.setToolTipText("Starts a queue run from this task entry on forward.");
        startQueueRunFromHereMenuItem.setEnabled(false);
        startQueueRunFromHereMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                startQueueRunFromHereMenuItemActionPerformed(evt);
            }
        });
        taskQueuePopupMenu.add(startQueueRunFromHereMenuItem);

        commandTreeNodePopupMenu.setFocusable(false);

        addCommandToQueueMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-add-task-16px.png"))); // NOI18N
        addCommandToQueueMenuItem.setText("Add Command to Queue");
        addCommandToQueueMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addCommandToQueueMenuItemActionPerformed(evt);
            }
        });
        commandTreeNodePopupMenu.add(addCommandToQueueMenuItem);

        serverTreeNodePopupMenu.setFocusable(false);

        disconnectServerMenuItem.setText("Disconnect Server");
        disconnectServerMenuItem.setToolTipText("Closes the connection to this server.");
        disconnectServerMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                disconnectServerMenuItemActionPerformed(evt);
            }
        });
        serverTreeNodePopupMenu.add(disconnectServerMenuItem);

        reconnectServerMenuItem.setText("Reconnect Server");
        reconnectServerMenuItem.setToolTipText("Re-establishes the connection to this server.");
        reconnectServerMenuItem.setEnabled(false);
        reconnectServerMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                reconnectServerMenuItemActionPerformed(evt);
            }
        });
        serverTreeNodePopupMenu.add(reconnectServerMenuItem);

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("SiLA Orchestrator");
        setIconImage(ICON_IMG);
        setLocationByPlatform(true);
        setPreferredSize(new java.awt.Dimension(1200, 600));
        setSize(new java.awt.Dimension(0, 0));
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });

        serverSplitPane.setContinuousLayout(true);

        serverPanel.setPreferredSize(new java.awt.Dimension(384, 220));
        java.awt.GridBagLayout jPanel1Layout = new java.awt.GridBagLayout();
        jPanel1Layout.columnWidths = new int[] {2};
        jPanel1Layout.rowHeights = new int[] {2};
        serverPanel.setLayout(jPanel1Layout);

        serverTreeScrollPane.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 2, 0));
        serverTreeScrollPane.setViewportView(serverFeatureTree);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weighty = 1.0;
        serverPanel.add(serverTreeScrollPane, gridBagConstraints);

        addServerBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/server-add.png"))); // NOI18N
        addServerBtn.setMnemonic('a');
        addServerBtn.setText("Add");
        addServerBtn.setToolTipText("Adds a SiLA server with a given IP address and port.");
        addServerBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addServerActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 0.2;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 0, 2);
        serverPanel.add(addServerBtn, gridBagConstraints);

        scanServerBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/network-scan-32px.png"))); // NOI18N
        scanServerBtn.setMnemonic('c');
        scanServerBtn.setText("Scan");
        scanServerBtn.setToolTipText("Scans the network for discoverable SiLA servers.");
        scanServerBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                scanNetworkActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 0.8;
        serverPanel.add(scanServerBtn, gridBagConstraints);

        serverSplitPane.setLeftComponent(serverPanel);

        mainPanel.setPreferredSize(new java.awt.Dimension(512, 409));
        mainPanel.setLayout(new javax.swing.BoxLayout(mainPanel, javax.swing.BoxLayout.PAGE_AXIS));

        mainPanelSplitPane.setDividerLocation(250);
        mainPanelSplitPane.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
        mainPanelSplitPane.setToolTipText("");
        mainPanelSplitPane.setContinuousLayout(true);

        java.awt.GridBagLayout taskQueuePanelLayout = new java.awt.GridBagLayout();
        taskQueuePanelLayout.columnWidths = new int[] {2};
        taskQueuePanelLayout.rowHeights = new int[] {4};
        taskQueuePanel.setLayout(taskQueuePanelLayout);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.gridheight = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 0.5;
        gridBagConstraints.weighty = 0.5;
        taskQueuePanel.add(taskQueueScrollPane, gridBagConstraints);

        showOrHideTableColumnBtn.setText("..."); // NOI18N
        showOrHideTableColumnBtn.setToolTipText("Show/hide table columns.");
        showOrHideTableColumnBtn.setMaximumSize(new java.awt.Dimension(64, 24));
        showOrHideTableColumnBtn.setMinimumSize(new java.awt.Dimension(36, 24));
        showOrHideTableColumnBtn.setPreferredSize(new java.awt.Dimension(36, 24));
        showOrHideTableColumnBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showOrHideTableColumnBtnActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        taskQueuePanel.add(showOrHideTableColumnBtn, gridBagConstraints);

        moveTaskUpBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/move-up.png"))); // NOI18N
        moveTaskUpBtn.setToolTipText("Move selcted task one place up in the queue order");
        moveTaskUpBtn.setEnabled(false);
        moveTaskUpBtn.setMaximumSize(new java.awt.Dimension(64, 1024));
        moveTaskUpBtn.setMinimumSize(new java.awt.Dimension(36, 36));
        moveTaskUpBtn.setPreferredSize(new java.awt.Dimension(36, 48));
        moveTaskUpBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                moveTaskUpBtnActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.VERTICAL;
        gridBagConstraints.weighty = 0.5;
        taskQueuePanel.add(moveTaskUpBtn, gridBagConstraints);

        moveTaskDownBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/move-down.png"))); // NOI18N
        moveTaskDownBtn.setToolTipText("Move selected task one place down in the queue order");
        moveTaskDownBtn.setEnabled(false);
        moveTaskDownBtn.setMaximumSize(new java.awt.Dimension(64, 1024));
        moveTaskDownBtn.setMinimumSize(new java.awt.Dimension(36, 36));
        moveTaskDownBtn.setPreferredSize(new java.awt.Dimension(36, 48));
        moveTaskDownBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                moveTaskDownBtnActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.VERTICAL;
        gridBagConstraints.weighty = 0.5;
        taskQueuePanel.add(moveTaskDownBtn, gridBagConstraints);

        removeTaskFromQueueBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/task-remove.png"))); // NOI18N
        removeTaskFromQueueBtn.setToolTipText("Remove selected task from queue");
        removeTaskFromQueueBtn.setEnabled(false);
        removeTaskFromQueueBtn.setMaximumSize(new java.awt.Dimension(64, 38));
        removeTaskFromQueueBtn.setMinimumSize(new java.awt.Dimension(36, 36));
        removeTaskFromQueueBtn.setPreferredSize(new java.awt.Dimension(36, 32));
        removeTaskFromQueueBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                removeTaskFromQueue(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.VERTICAL;
        taskQueuePanel.add(removeTaskFromQueueBtn, gridBagConstraints);

        mainPanelSplitPane.setLeftComponent(taskQueuePanel);
        mainPanelSplitPane.setRightComponent(presenterScrollPane);

        mainPanel.add(mainPanelSplitPane);

        serverSplitPane.setRightComponent(mainPanel);

        getContentPane().add(serverSplitPane, java.awt.BorderLayout.CENTER);

        toolBar.setFloatable(false);
        toolBar.setRollover(true);

        openFileBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/document-open.png"))); // NOI18N
        openFileBtn.setMnemonic('o');
        openFileBtn.setText("Open");
        openFileBtn.setToolTipText("Opens a *.silo file.");
        openFileBtn.setFocusable(false);
        openFileBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        openFileBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        openFileBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openFileActionPerformed(evt);
            }
        });
        toolBar.add(openFileBtn);

        openAndAppendFileBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/document-open-append-24px.png"))); // NOI18N
        openAndAppendFileBtn.setText("Open Add");
        openAndAppendFileBtn.setToolTipText("Loads the content of a *.silo-file and appends it to end of the current queue.");
        openAndAppendFileBtn.setFocusable(false);
        openAndAppendFileBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        openAndAppendFileBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        openAndAppendFileBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openAndAppendFileActionPerformed(evt);
            }
        });
        toolBar.add(openAndAppendFileBtn);

        saveFileBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/document-save.png"))); // NOI18N
        saveFileBtn.setMnemonic('s');
        saveFileBtn.setText("Save");
        saveFileBtn.setToolTipText("Saves a current queue into a *.silo file.");
        saveFileBtn.setEnabled(false);
        saveFileBtn.setFocusable(false);
        saveFileBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        saveFileBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        saveFileBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveFileActionPerformed(evt);
            }
        });
        toolBar.add(saveFileBtn);

        filler1.setMaximumSize(new java.awt.Dimension(10, 32767));
        filler1.setMinimumSize(new java.awt.Dimension(10, 0));
        filler1.setPreferredSize(new java.awt.Dimension(10, 0));
        toolBar.add(filler1);
        toolBar.add(toolBarSeparator1);

        filler2.setMaximumSize(new java.awt.Dimension(10, 32767));
        filler2.setMinimumSize(new java.awt.Dimension(10, 0));
        filler2.setPreferredSize(new java.awt.Dimension(10, 0));
        toolBar.add(filler2);

        addDelayBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/delay-add.png"))); // NOI18N
        addDelayBtn.setText("Add Delay");
        addDelayBtn.setToolTipText("Adds a delay in the task queue.");
        addDelayBtn.setFocusable(false);
        addDelayBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        addDelayBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        addDelayBtn.setTransferHandler(new TaskExportTransferHandler(() -> (new DelayTask())));
        addDelayBtn.setDropTarget(null);
        addDelayBtn.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseDragged(java.awt.event.MouseEvent evt) {
                addTaskBtnMouseDragged(evt);
            }
        });
        addDelayBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addDelayTaskActionPerformed(evt);
            }
        });
        toolBar.add(addDelayBtn);

        addLocalExecBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/exec-add.png"))); // NOI18N
        addLocalExecBtn.setText("Add Exec");
        addLocalExecBtn.setToolTipText("Adds a local executable in the task queue.");
        addLocalExecBtn.setFocusable(false);
        addLocalExecBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        addLocalExecBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        addLocalExecBtn.setTransferHandler(new TaskExportTransferHandler(() -> (new LocalExecTask())));
        addLocalExecBtn.setDropTarget(null);
        addLocalExecBtn.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseDragged(java.awt.event.MouseEvent evt) {
                addTaskBtnMouseDragged(evt);
            }
        });
        addLocalExecBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addLocalExecTaskActionPerformed(evt);
            }
        });
        toolBar.add(addLocalExecBtn);

        clearQueueBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-clear.png"))); // NOI18N
        clearQueueBtn.setText("Clear Queue");
        clearQueueBtn.setToolTipText("Removes all entries from the current task queue.");
        clearQueueBtn.setEnabled(false);
        clearQueueBtn.setFocusable(false);
        clearQueueBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        clearQueueBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        clearQueueBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clearQueueActionPerformed(evt);
            }
        });
        toolBar.add(clearQueueBtn);

        exportQueueBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-export.png"))); // NOI18N
        exportQueueBtn.setText("Export Queue");
        exportQueueBtn.setToolTipText("Exports current queue data as *.csv table.");
        exportQueueBtn.setEnabled(false);
        exportQueueBtn.setFocusable(false);
        exportQueueBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        exportQueueBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        exportQueueBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportQueueActionPerformed(evt);
            }
        });
        toolBar.add(exportQueueBtn);

        filler3.setMaximumSize(new java.awt.Dimension(10, 32767));
        filler3.setMinimumSize(new java.awt.Dimension(10, 0));
        filler3.setPreferredSize(new java.awt.Dimension(10, 0));
        toolBar.add(filler3);
        toolBar.add(toolBarSeparator2);

        filler4.setMaximumSize(new java.awt.Dimension(10, 32767));
        filler4.setMinimumSize(new java.awt.Dimension(10, 0));
        filler4.setPreferredSize(new java.awt.Dimension(10, 0));
        toolBar.add(filler4);

        startQueueRunBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-run-start-24px.png"))); // NOI18N
        startQueueRunBtn.setMnemonic('r');
        startQueueRunBtn.setText("Start Run");
        startQueueRunBtn.setToolTipText("Starts to run the entire task queue from top to bottom.");
        startQueueRunBtn.setEnabled(false);
        startQueueRunBtn.setFocusable(false);
        startQueueRunBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        startQueueRunBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        startQueueRunBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                startQueueRunActionPerformed(evt);
            }
        });
        toolBar.add(startQueueRunBtn);

        stopQueueRunBtn.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-run-stop-24px.png"))); // NOI18N
        stopQueueRunBtn.setMnemonic('t');
        stopQueueRunBtn.setText("Stop Run");
        stopQueueRunBtn.setToolTipText("Aborts the current queue run.");
        stopQueueRunBtn.setEnabled(false);
        stopQueueRunBtn.setFocusable(false);
        stopQueueRunBtn.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        stopQueueRunBtn.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        stopQueueRunBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                stopQueueRunActionPerformed(evt);
            }
        });
        toolBar.add(stopQueueRunBtn);

        getContentPane().add(toolBar, java.awt.BorderLayout.PAGE_START);

        fileMenu.setMnemonic('f');
        fileMenu.setText("File");

        openMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        openMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/document-open-16px.png"))); // NOI18N
        openMenuItem.setMnemonic('o');
        openMenuItem.setText("Open");
        openMenuItem.setToolTipText("Loads the content of a *.silo-file into the task queue.");
        openMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openFileActionPerformed(evt);
            }
        });
        fileMenu.add(openMenuItem);

        openAndAppendMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/document-open-append-16px.png"))); // NOI18N
        openAndAppendMenuItem.setText("Open and Add");
        openAndAppendMenuItem.setToolTipText("Loads the content of a *.silo-file and appends it to end of the current queue.");
        openAndAppendMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openAndAppendFileActionPerformed(evt);
            }
        });
        fileMenu.add(openAndAppendMenuItem);

        saveMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        saveMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/document-save-16px.png"))); // NOI18N
        saveMenuItem.setMnemonic('s');
        saveMenuItem.setText("Save");
        saveMenuItem.setEnabled(false);
        saveMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveFileActionPerformed(evt);
            }
        });
        fileMenu.add(saveMenuItem);

        saveAsMenuItem.setMnemonic('a');
        saveAsMenuItem.setText("Save As ...");
        saveAsMenuItem.setDisplayedMnemonicIndex(5);
        saveAsMenuItem.setEnabled(false);
        saveAsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveAsActionPerformed(evt);
            }
        });
        fileMenu.add(saveAsMenuItem);

        exitMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Q, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        exitMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/application-exit-16px.png"))); // NOI18N
        exitMenuItem.setMnemonic('x');
        exitMenuItem.setText("Exit");
        exitMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exitMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(exitMenuItem);

        menuBar.add(fileMenu);

        serverMenu.setMnemonic('v');
        serverMenu.setText("Server");

        addServerMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/server-add-16px.png"))); // NOI18N
        addServerMenuItem.setMnemonic('a');
        addServerMenuItem.setText("Add Server");
        addServerMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addServerActionPerformed(evt);
            }
        });
        serverMenu.add(addServerMenuItem);

        scanNetworkMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/network-scan-16px.png"))); // NOI18N
        scanNetworkMenuItem.setMnemonic('c');
        scanNetworkMenuItem.setText("Scan Network");
        scanNetworkMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                scanNetworkActionPerformed(evt);
            }
        });
        serverMenu.add(scanNetworkMenuItem);

        menuBar.add(serverMenu);

        tasksMenu.setMnemonic('k');
        tasksMenu.setText("Tasks");

        clearQueueMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-clear-16px.png"))); // NOI18N
        clearQueueMenuItem.setMnemonic('c');
        clearQueueMenuItem.setText("Clear Queue");
        clearQueueMenuItem.setToolTipText("Clears all entries from the current task queue.");
        clearQueueMenuItem.setEnabled(false);
        clearQueueMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clearQueueActionPerformed(evt);
            }
        });
        tasksMenu.add(clearQueueMenuItem);

        exportQueueMenuItem.setMnemonic('x');
        exportQueueMenuItem.setText("Export Queue");
        exportQueueMenuItem.setToolTipText("Exports current queue data as *.csv table.");
        exportQueueMenuItem.setEnabled(false);
        exportQueueMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportQueueActionPerformed(evt);
            }
        });
        tasksMenu.add(exportQueueMenuItem);

        startQueueRunMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-run-start-16px.png"))); // NOI18N
        startQueueRunMenuItem.setMnemonic('r');
        startQueueRunMenuItem.setText("Start Queue Run");
        startQueueRunMenuItem.setToolTipText("Start executing the current task queue.");
        startQueueRunMenuItem.setEnabled(false);
        startQueueRunMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                startQueueRunActionPerformed(evt);
            }
        });
        tasksMenu.add(startQueueRunMenuItem);

        stopQueueRunMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/queue-run-stop-16px.png"))); // NOI18N
        stopQueueRunMenuItem.setMnemonic('t');
        stopQueueRunMenuItem.setText("Stop Queue Run");
        stopQueueRunMenuItem.setToolTipText("Aborts the current queue run.");
        stopQueueRunMenuItem.setEnabled(false);
        stopQueueRunMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                stopQueueRunActionPerformed(evt);
            }
        });
        tasksMenu.add(stopQueueRunMenuItem);

        addDelayTaskMenuItem.setText("Add Delay");
        addDelayTaskMenuItem.setToolTipText("Add a delay to the task queue.");
        addDelayTaskMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addDelayTaskActionPerformed(evt);
            }
        });
        tasksMenu.add(addDelayTaskMenuItem);

        addLocalExecTaskMenuItem.setText("Add Executable");
        addLocalExecTaskMenuItem.setToolTipText("Adds a starter for a local executable or a script.");
        addLocalExecTaskMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addLocalExecTaskActionPerformed(evt);
            }
        });
        tasksMenu.add(addLocalExecTaskMenuItem);

        menuBar.add(tasksMenu);

        helpMenu.setMnemonic('h');
        helpMenu.setText("Help");

        aboutMenuItem.setIcon(new javax.swing.ImageIcon(getClass().getResource("/icons/sila-orchestrator-16px.png"))); // NOI18N
        aboutMenuItem.setMnemonic('a');
        aboutMenuItem.setText("About");
        aboutMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                aboutMenuItemActionPerformed(evt);
            }
        });
        helpMenu.add(aboutMenuItem);

        menuBar.add(helpMenu);

        setJMenuBar(menuBar);

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void initTaskQueueTable() {
        taskQueueTable.setParamsPane(presenterScrollPane);
        taskQueueTable.setComponentPopupMenu(taskQueuePopupMenu);
        taskQueueScrollPane.setViewportView(taskQueueTable);
        taskQueueTable.getModel().addTableModelListener((TableModelEvent evt) -> {
            final int evtType = evt.getType();
            if (evtType == TableModelEvent.INSERT) {
                final TableModel model = (TableModel) evt.getSource();
                if (model.getRowCount() == 1) {
                    enableTaskQueueOperationControls();
                }
            } else if (evtType == TableModelEvent.DELETE) {
                final TableModel model = (TableModel) evt.getSource();
                if (model.getRowCount() <= 0) {
                    disableTaskQueueOperationControls();
                    presenterScrollPane.setViewportView(null);
                }
            }
        });
        taskQueueTable.getSelectionModel().addListSelectionListener((ListSelectionEvent lse) -> {
            if (lse.getValueIsAdjusting()) {
                return;
            }
            viewSelectedTask();
        });
        taskQueueTable.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeTask");
        taskQueueTable.getActionMap().put("removeTask", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                removeTaskFromQueue(evt);
            }
        });
        connectionManager.addConnectionListener(taskQueueTable);
    }

    private void initServerTree() {
        serverFeatureTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent me) {
                final int button = me.getButton();
                if (button != MouseEvent.BUTTON1 && button != MouseEvent.BUTTON3) {
                    // Anything besides the left and rigth mouse button is invalid.
                    return;
                }
                final TreePath path = serverFeatureTree.getPathForLocation(me.getX(), me.getY());
                if (path == null) {
                    return;
                }
                serverFeatureTree.setSelectionPath(path);
                if (button == MouseEvent.BUTTON3) { // on right click
                    final Object node = path.getLastPathComponent();
                    if (node instanceof CommandTreeNode) {
                        commandTreeNodePopupMenu.show(serverFeatureTree, me.getX(), me.getY());
                    } else if (node instanceof ServerTreeNode) {
                        final ServerTreeNode serverNode = (ServerTreeNode) node;
                        final boolean isServerOnline = serverNode.isOnline();
                        disconnectServerMenuItem.setEnabled(isServerOnline);
                        reconnectServerMenuItem.setEnabled(!isServerOnline);
                        serverTreeNodePopupMenu.show(serverFeatureTree, me.getX(), me.getY());
                    }
                }
                taskQueueTable.clearSelection();
                presenterScrollPane.setViewportView(serverFeatureTree.getPresenter());
            }
        });
        connectionManager.addConnectionListener(serverFeatureTree);
    }

    /**
     * Loads the presenter of the selected task-queue entry into the context sensitive view panel
     * and sets the state of the affected GUI controls accordingly.
     */
    private void viewSelectedTask() {
        int selectedRowIdx = taskQueueTable.getSelectedRow();
        if (selectedRowIdx < 0) {
            return;
        }
        if (!serverFeatureTree.isSelectionEmpty()) {
            serverFeatureTree.getSelectionModel().clearSelection();
        }
        int rowCount = taskQueueTable.getRowCount();
        if (rowCount > 1) {
            moveTaskUpBtn.setEnabled(selectedRowIdx > 0);
            moveTaskDownBtn.setEnabled(selectedRowIdx < rowCount - 1);
        } else {
            moveTaskUpBtn.setEnabled(false);
            moveTaskDownBtn.setEnabled(false);
        }
        final boolean isTaskRemoveEnabled = (rowCount > 0);
        removeTaskFromQueueBtn.setEnabled(isTaskRemoveEnabled);
        removeTaskFromQueueMenuItem.setEnabled(isTaskRemoveEnabled);
        execRowEntryMenuItem.setEnabled(isTaskRemoveEnabled);
        final QueueTask entry = taskQueueTable.getTaskFromRow(selectedRowIdx);
        if (entry == null) {
            return;
        }
        presenterScrollPane.setViewportView(entry.getPresenter());
    }

    private void exitMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exitMenuItemActionPerformed
        connectionManager.close();
        System.exit(0);
    }//GEN-LAST:event_exitMenuItemActionPerformed

    private void addServerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addServerActionPerformed
        addServerDialog.pack();
        addServerDialog.setVisible(true);
    }//GEN-LAST:event_addServerActionPerformed

    private void aboutMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_aboutMenuItemActionPerformed
        aboutDialog.pack();
        aboutDialog.setVisible(true);
    }//GEN-LAST:event_aboutMenuItemActionPerformed

    private void serverDialogCancelBtnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_serverDialogCancelBtnActionPerformed
        addServerDialog.setVisible(false);
        addServerDialog.dispose();
    }//GEN-LAST:event_serverDialogCancelBtnActionPerformed

    private void serverDialogConnectBtnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_serverDialogConnectBtnActionPerformed
        addSpecificServer();
    }//GEN-LAST:event_serverDialogConnectBtnActionPerformed

    /**
     * Scans the network for available SiLA-Servers which are enabled for discovery. The
     * scan-routine runs in a dedicated thread to avoid freezing while scanning. The synchronization
     * with the involved GUI components has to be done with
     * <code>SwingUtilities.invokeLater(() -> { ... });</code> to grant thread safety.
     */
    private void scanNetworkActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_scanNetworkActionPerformed
        scanServerBtn.setEnabled(false);
        final Runnable scan = () -> {
            connectionManager.scanNetwork();

            // update components in the GUI thread
            SwingUtilities.invokeLater(() -> {
                serverFeatureTree.updateTreeView();
                scanServerBtn.setEnabled(true);
            });
        };
        new Thread(scan).start();
    }//GEN-LAST:event_scanNetworkActionPerformed

    private void serverPortFormattedTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_serverPortFormattedTextFieldActionPerformed
        addSpecificServer();
    }//GEN-LAST:event_serverPortFormattedTextFieldActionPerformed

    private void serverAddressTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_serverAddressTextFieldActionPerformed
        // set cursor to the next text field when enter was pressed
        serverPortFormattedTextField.requestFocusInWindow();
    }//GEN-LAST:event_serverAddressTextFieldActionPerformed

    private void formWindowClosing(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosing
        connectionManager.close();
    }//GEN-LAST:event_formWindowClosing

    private void execRowEntryMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_execRowEntryMenuItemActionPerformed
        int selectedRowIdx = taskQueueTable.getSelectedRow();
        if (selectedRowIdx < 0) {
            return;
        }
        final QueueTask entry = taskQueueTable.getTaskFromRow(selectedRowIdx);
        new Thread(entry).start();
    }//GEN-LAST:event_execRowEntryMenuItemActionPerformed

    private void moveTaskUpBtnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_moveTaskUpBtnActionPerformed
        int selectedRowIdx = taskQueueTable.getSelectedRow();
        if (selectedRowIdx < 0) {
            return;
        } else if (selectedRowIdx <= 1) {
            moveTaskUpBtn.setEnabled(false);
        }
        taskQueueTable.moveRow(selectedRowIdx, selectedRowIdx - 1);
        taskQueueTable.changeSelection(selectedRowIdx - 1, Column.TASK_ID.ordinal(), false, false);
        moveTaskDownBtn.setEnabled(true);
    }//GEN-LAST:event_moveTaskUpBtnActionPerformed

    private void moveTaskDownBtnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_moveTaskDownBtnActionPerformed
        int selectedRowIdx = taskQueueTable.getSelectedRow();
        int rowCount = taskQueueTable.getRowCount();
        if (selectedRowIdx < 0 || selectedRowIdx >= rowCount - 1) {
            return;
        } else if (selectedRowIdx >= rowCount - 2) {
            moveTaskDownBtn.setEnabled(false);
        }
        taskQueueTable.moveRow(selectedRowIdx, selectedRowIdx + 1);
        taskQueueTable.changeSelection(selectedRowIdx + 1, Column.TASK_ID.ordinal(), false, false);
        moveTaskUpBtn.setEnabled(true);
    }//GEN-LAST:event_moveTaskDownBtnActionPerformed

    private void removeTaskFromQueue(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_removeTaskFromQueue
        int selectedRowIdx = taskQueueTable.getSelectedRow();
        if (selectedRowIdx < 0) {
            return;
        }
        moveTaskUpBtn.setEnabled(false);
        moveTaskDownBtn.setEnabled(false);
        removeTaskFromQueueBtn.setEnabled(false);
        removeTaskFromQueueMenuItem.setEnabled(false);
        execRowEntryMenuItem.setEnabled(false);
        taskQueueTable.removeRow(selectedRowIdx);
        presenterScrollPane.setViewportView(null);
    }//GEN-LAST:event_removeTaskFromQueue

    private void saveFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveFileActionPerformed
        if (!wasSaved || outFilePath == null) {
            saveAsActionPerformed(evt);
        } else {
            // TODO: give the user some kind of notification that the file was saved
            final TaskQueueData tqd = TaskQueueData.createFromTaskQueue(taskQueueTable);
            try {
                TaskQueueData.writeToFile(outFilePath, tqd);
                log.info("Saved " + outFilePath);
            } catch (IOException ex) {
                log.error(ex.getMessage());
            }
        }
    }//GEN-LAST:event_saveFileActionPerformed

    private void saveAsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveAsActionPerformed
        final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH.mm");
        saveAsFileChooser.setSelectedFile(new File(LocalDateTime.now().format(dtf) + ".silo"));
        int retVal = saveAsFileChooser.showSaveDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            final Path outPath = Paths.get(saveAsFileChooser.getSelectedFile().getAbsolutePath());
            outFilePath = outPath;
            int userDesition = JOptionPane.OK_OPTION;
            if (Files.exists(outPath)) {
                userDesition = JOptionPane.showConfirmDialog(this,
                        "File \"" + outPath.getFileName() + "\" already exists in \""
                        + outPath.getParent() + "\"!\n"
                        + "Do you want to overwrite the existing file?",
                        "Overwrite File",
                        JOptionPane.INFORMATION_MESSAGE,
                        JOptionPane.YES_NO_CANCEL_OPTION);
            }

            if (userDesition == JOptionPane.OK_OPTION) {
                TaskQueueData tqd = TaskQueueData.createFromTaskQueue(taskQueueTable);
                try {
                    TaskQueueData.writeToFile(outPath, tqd);
                    wasSaved = true;
                    log.info("Saved as file " + outPath);
                } catch (IOException ex) {
                    log.error(ex.getMessage());
                }
            }
        }
    }//GEN-LAST:event_saveAsActionPerformed

    private void openFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openFileActionPerformed
        int retVal = openFileChooser.showOpenDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            final File file = openFileChooser.getSelectedFile();
            final TaskQueueData tqd;
            try {
                tqd = TaskQueueData.createFromFile(file.getAbsolutePath());
            } catch (final Exception ex) {
                JOptionPane.showMessageDialog(this,
                        ex.getMessage(),
                        "Import Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (!wasSaved && !taskQueueTable.isEmpty()) {
                int optionVal = JOptionPane.showConfirmDialog(
                        this,
                        "The current task queue is not empty and will be overwritten.\n"
                        + "Do you want to overwrite the task queue?",
                        "Overwrite Queue?",
                        JOptionPane.YES_NO_CANCEL_OPTION);

                if (optionVal != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            clearQueueActionPerformed(evt);
            tqd.importToTaskQueue(taskQueueTable);
        }
    }//GEN-LAST:event_openFileActionPerformed

    private void clearQueueActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clearQueueActionPerformed
        taskQueueTable.clearTable();
        outFilePath = null;
        wasSaved = false;
    }//GEN-LAST:event_clearQueueActionPerformed

    private void aboutDialogCloseBtnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_aboutDialogCloseBtnActionPerformed
        aboutDialog.setVisible(false);
        aboutDialog.dispose();
    }//GEN-LAST:event_aboutDialogCloseBtnActionPerformed

    /**
     * Adds a delay task to the task queue.
     *
     * @param evt The fired event.
     */
    private void addDelayTaskActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addDelayTaskActionPerformed
        taskQueueTable.addTask(new DelayTask());
    }//GEN-LAST:event_addDelayTaskActionPerformed

    private void addLocalExecTaskActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addLocalExecTaskActionPerformed
        taskQueueTable.addTask(new LocalExecTask());
    }//GEN-LAST:event_addLocalExecTaskActionPerformed

    private void addTaskBtnMouseDragged(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_addTaskBtnMouseDragged
        JComponent comp = (JComponent) evt.getSource();
        TransferHandler handler = comp.getTransferHandler();
        handler.exportAsDrag(comp, evt, TransferHandler.COPY);
    }//GEN-LAST:event_addTaskBtnMouseDragged

    private void showOrHideTableColumnBtnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showOrHideTableColumnBtnActionPerformed
        final JPopupMenu columnPopupMenu = taskQueueTable.getColumnHeaderPopupMenu();
        if (!columnPopupMenu.isVisible()) {
            columnPopupMenu.show(showOrHideTableColumnBtn, 0, showOrHideTableColumnBtn.getHeight());
        } else {
            columnPopupMenu.setVisible(false);
        }
    }//GEN-LAST:event_showOrHideTableColumnBtnActionPerformed

    private void exportQueueActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportQueueActionPerformed
        final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH.mm");
        saveAsFileChooser.setSelectedFile(new File(LocalDateTime.now().format(dtf) + ".csv"));
        int retVal = saveAsFileChooser.showSaveDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            final Path outPath = Paths.get(saveAsFileChooser.getSelectedFile().getAbsolutePath());
            int userDesition = JOptionPane.OK_OPTION;
            if (Files.exists(outPath)) {
                userDesition = JOptionPane.showConfirmDialog(this,
                        "File \"" + outPath.getFileName() + "\" already exists in \""
                        + outPath.getParent() + "\"!\n"
                        + "Do you want to overwrite the existing file?",
                        "Overwrite File?",
                        JOptionPane.YES_NO_CANCEL_OPTION);
            }

            if (userDesition == JOptionPane.OK_OPTION) {
                final StringBuilder sb = new StringBuilder();
                taskQueueTable.exportTableContentsAsCsv(sb);
                try {
                    Files.writeString(outPath, sb);
                    log.info("Exported file " + outPath);
                } catch (final IOException ex) {
                    log.error(ex.getMessage());
                }
            }
        }
    }//GEN-LAST:event_exportQueueActionPerformed

    private void startQueueRunActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_startQueueRunActionPerformed
        if (isQueueOnExecution) {
            // queue is already running
            return;
        }

        disableStartRunControls();
        taskQueueTable.resetAllTaskStates();
        isQueueOnExecution = true;

        final Runnable queueRunner = () -> {
            for (int i = 0; i < taskQueueTable.getRowCount(); i++) {
                if (!isQueueOnExecution) {
                    break;
                }

                final QueueTask task = taskQueueTable.getTaskFromRow(i);
                currentlyExecutedTaskThread = new Thread(task);
                currentlyExecutedTaskThread.start();
                try {
                    currentlyExecutedTaskThread.join();
                } catch (InterruptedException ex) {
                    log.error(ex.getMessage());
                }

                if (task.getState() != TaskState.FINISHED_SUCCESS) {
                    // apply execution policy
                    if (taskQueueTable.getTaskPolicyFromRow(i) == ExecPolicy.HALT_AFTER_ERROR) {
                        break;
                    }
                }
            }
            currentlyExecutedTaskThread = null;

            SwingUtilities.invokeLater(() -> {
                enableStartRunControls();
            });
            isQueueOnExecution = false;
        };
        new Thread(queueRunner).start();
    }//GEN-LAST:event_startQueueRunActionPerformed

    /**
     * Stops the current run of the task queue. If the execution is stopped and a task is currently
     * running, the active task gets 2 seconds time to complete before an interrupt is signaled,
     * which causes a <code>InterruptedException</code> inside the thread.
     *
     * @param evt The fired event.
     */
    private void stopQueueRunActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_stopQueueRunActionPerformed
        if (!isQueueOnExecution) {
            // there is nothing to stop
            return;
        }

        stopQueueRunBtn.setEnabled(false);
        stopQueueRunMenuItem.setEnabled(false);
        isQueueOnExecution = false;
        log.info("Aborted queue execution by user.");
        /**
         * Use a dedicated thread for the abortion process, since the user can pile up events by
         * spamming the button due to the delay inside the cancellation routine.
         */
        final Runnable abortRunner = () -> {
            // give the executing thread 2 seconds time to finish before sending an interrupt
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                log.error(ex.getMessage());
            }
            if (currentlyExecutedTaskThread != null
                    && !currentlyExecutedTaskThread.isInterrupted()) {
                currentlyExecutedTaskThread.interrupt();
            }

            SwingUtilities.invokeLater(() -> {
                enableStartRunControls();
            });
        };
        new Thread(abortRunner).start();
    }//GEN-LAST:event_stopQueueRunActionPerformed

    private void startQueueRunFromHereMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_startQueueRunFromHereMenuItemActionPerformed
        int selectedRowIdx = taskQueueTable.getSelectedRow();
        if (selectedRowIdx < 0) {
            return;
        }

        if (isQueueOnExecution) {
            // queue is already running
            return;
        }

        disableStartRunControls();
        taskQueueTable.resetAllTaskStates();
        isQueueOnExecution = true;

        final Runnable queueRunner = () -> {
            for (int i = selectedRowIdx; i < taskQueueTable.getRowCount(); i++) {
                if (!isQueueOnExecution) {
                    break;
                }

                final QueueTask task = taskQueueTable.getTaskFromRow(i);
                currentlyExecutedTaskThread = new Thread(task);
                currentlyExecutedTaskThread.start();
                try {
                    currentlyExecutedTaskThread.join();
                } catch (InterruptedException ex) {
                    log.error(ex.getMessage());
                }

                if (task.getState() != TaskState.FINISHED_SUCCESS) {
                    // apply execution policy
                    if (taskQueueTable.getTaskPolicyFromRow(i) == ExecPolicy.HALT_AFTER_ERROR) {
                        break;
                    }
                }
            }
            currentlyExecutedTaskThread = null;

            SwingUtilities.invokeLater(() -> {
                enableStartRunControls();
            });
            isQueueOnExecution = false;
        };
        new Thread(queueRunner).start();
    }//GEN-LAST:event_startQueueRunFromHereMenuItemActionPerformed

    private void openAndAppendFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openAndAppendFileActionPerformed
        int retVal = openFileChooser.showOpenDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            final File file = openFileChooser.getSelectedFile();
            final TaskQueueData tqd;
            try {
                tqd = TaskQueueData.createFromFile(file.getAbsolutePath());
            } catch (final Exception ex) {
                JOptionPane.showMessageDialog(this,
                        ex.getMessage(),
                        "Import Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            tqd.importToTaskQueue(taskQueueTable);
        }
    }//GEN-LAST:event_openAndAppendFileActionPerformed

    private void viewLicenseBtnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_viewLicenseBtnActionPerformed
        licenseDialog.pack();
        licenseDialog.setVisible(true);
    }//GEN-LAST:event_viewLicenseBtnActionPerformed

    private void addCommandToQueueMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addCommandToQueueMenuItemActionPerformed
        final DefaultMutableTreeNode node = (DefaultMutableTreeNode) serverFeatureTree.getLastSelectedPathComponent();
        if (node == null) {
            return;
        }

        if (node.isLeaf()) {
            if (node instanceof CommandTreeNode) {
                final CommandTreeNode cmdNode = (CommandTreeNode) node;
                // use the selected node to create a new table entry.
                taskQueueTable.addCommandTask(cmdNode.createTableEntry());
            }
        }
    }//GEN-LAST:event_addCommandToQueueMenuItemActionPerformed

    private void disconnectServerMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_disconnectServerMenuItemActionPerformed
        final DefaultMutableTreeNode node = (DefaultMutableTreeNode) serverFeatureTree.getLastSelectedPathComponent();
        if (node == null) {
            return;
        }

        if (!node.isLeaf()) {
            if (node instanceof ServerTreeNode) {
                final ServerTreeNode serverNode = (ServerTreeNode) node;
                if (!serverNode.isOnline()) {
                    return;
                }
                connectionManager.removeServer(serverNode.getServerUuid());
            }
        }
    }//GEN-LAST:event_disconnectServerMenuItemActionPerformed

    private void reconnectServerMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_reconnectServerMenuItemActionPerformed
        final DefaultMutableTreeNode node = (DefaultMutableTreeNode) serverFeatureTree.getLastSelectedPathComponent();
        if (node == null) {
            return;
        }

        if (!node.isLeaf()) {
            if (node instanceof ServerTreeNode) {
                final ServerTreeNode serverNode = (ServerTreeNode) node;
                if (serverNode.isOnline()) {
                    return;
                }

                try {
                    connectionManager.reconnectServer(serverNode.getServerUuid());
                } catch (ServerAdditionException ex) {
                    log.warn(ex.getMessage());
                }
            }
        }
    }//GEN-LAST:event_reconnectServerMenuItemActionPerformed

    private void openCertFileBtnActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openCertFileBtnActionPerformed
        int retVal = certificateFileChooser.showOpenDialog(this);
        if (retVal == JFileChooser.APPROVE_OPTION) {
            final File file = certificateFileChooser.getSelectedFile();
            final X509Certificate cert;
            try {
                cert = readCertificate(file);
                certificateStr = writeCertificateToString(cert);
            } catch (final Exception ex) {
                serverAddErrorEditorPane.setText("Certificate import error: " + ex.getMessage());
                certSerialNumberTextField.setText("<Serial Number>");
                return;
            }
            certSerialNumberTextField.setText(cert.getSerialNumber().toString(16));
        }
    }//GEN-LAST:event_openCertFileBtnActionPerformed

    private void enableStartRunControls() {
        stopQueueRunBtn.setEnabled(false);
        stopQueueRunMenuItem.setEnabled(false);

        startQueueRunBtn.setEnabled(true);
        startQueueRunMenuItem.setEnabled(true);
        startQueueRunFromHereMenuItem.setEnabled(true);
    }

    private void disableStartRunControls() {
        startQueueRunBtn.setEnabled(false);
        startQueueRunMenuItem.setEnabled(false);
        startQueueRunFromHereMenuItem.setEnabled(false);

        stopQueueRunBtn.setEnabled(true);
        stopQueueRunMenuItem.setEnabled(true);
    }

    /**
     * Enables all the GUI controls which actions can be applied on entries in the task queue. This
     * function is to enable user interaction after the task queue was set to a valid state (e.g.
     * queue is not empty anymore).
     */
    private void enableTaskQueueOperationControls() {
        startQueueRunBtn.setEnabled(true);
        startQueueRunMenuItem.setEnabled(true);
        startQueueRunFromHereMenuItem.setEnabled(true);
        clearQueueMenuItem.setEnabled(true);
        clearQueueBtn.setEnabled(true);
        exportQueueMenuItem.setEnabled(true);
        exportQueueBtn.setEnabled(true);
        saveFileBtn.setEnabled(true);
        saveMenuItem.setEnabled(true);
        saveAsMenuItem.setEnabled(true);
    }

    /**
     * Disables all the GUI controls which actions relay on entries in the task queue. This function
     * is supposed to be used when the table is empty or in an locked state and actions, like saving
     * or executing tasks, make no sense for the user.
     */
    private void disableTaskQueueOperationControls() {
        startQueueRunBtn.setEnabled(false);
        startQueueRunMenuItem.setEnabled(false);
        startQueueRunFromHereMenuItem.setEnabled(false);
        clearQueueMenuItem.setEnabled(false);
        clearQueueBtn.setEnabled(false);
        exportQueueMenuItem.setEnabled(false);
        exportQueueBtn.setEnabled(false);
        saveFileBtn.setEnabled(false);
        saveMenuItem.setEnabled(false);
        saveAsMenuItem.setEnabled(false);
        moveTaskUpBtn.setEnabled(false);
        moveTaskDownBtn.setEnabled(false);
        removeTaskFromQueueBtn.setEnabled(false);
        removeTaskFromQueueMenuItem.setEnabled(false);
        execRowEntryMenuItem.setEnabled(false);
    }

    /**
     * The program entry function which determines the operation mode (GUI or CLI). If the program
     * gets started without any arguments, the Graphical User Interface (GUI) is invoked, otherwise
     * the program operation happens solely within the Command-line Interface (CLI) aka the console.
     *
     * @param args The command-line arguments.
     *
     * @see CommandlineControls
     * @see CommandlineArguments
     */
    public static void main(String args[]) {
        try {
            // retrieve version info from the maven git plug-in
            GIT_PROPS.load(OrchestratorGui.class.getClassLoader().getResourceAsStream("git.properties"));
            connectionManager = ConnectionManager.getInstance();
        } catch (final IOException ex) {
            System.err.println(ex.getMessage());
            System.exit(-1);
        }

        if (args.length > 0) {
            // arguments were set, so we handel erverything in command-line and ditch the GUI stuff
            Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.WARN);
            System.out.println("\n");

            final CommandlineArguments cmdArgs;
            try {
                cmdArgs = CommandlineArguments.createFromArgs(args);
            } catch (final IllegalArgumentException ex) {
                System.err.println(ex.getMessage());
                connectionManager.close();
                System.exit(-1);
                return;
            }
            final CommandlineControls cmdCtrls = new CommandlineControls(GIT_PROPS, connectionManager);
            int exitVal = cmdCtrls.processArgs(cmdArgs);
            connectionManager.close();
            System.exit(exitVal);
        }

        final ToolTipManager ttmSharedInst = ToolTipManager.sharedInstance();
        ttmSharedInst.setInitialDelay(300);
        ttmSharedInst.setDismissDelay(Integer.MAX_VALUE);

        final String osName = System.getProperty("os.name");
        final String laf;
        if (osName.startsWith("Windows")) {
            laf = "Windows";
        } else if (osName.startsWith("Linux")) {
            laf = "GTK+";
        } else {
            laf = "Nimbus";
        }

        try {
            for (final UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if (info.getName().equals(laf)) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException
                | InstantiationException
                | IllegalAccessException
                | javax.swing.UnsupportedLookAndFeelException ex) {
            System.err.println(ex.getMessage());
        }

        // Create and display the form
        SwingUtilities.invokeLater(() -> {
            new OrchestratorGui().setVisible(true);
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private final javax.swing.JDialog aboutDialog = new javax.swing.JDialog();
    private final javax.swing.JButton aboutDialogCloseBtn = new javax.swing.JButton();
    private final javax.swing.JTextPane aboutInfoTextPane = new javax.swing.JTextPane();
    private final javax.swing.JLabel aboutLabel = new javax.swing.JLabel();
    private final javax.swing.JMenuItem aboutMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JMenuItem addCommandToQueueMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton addDelayBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem addDelayTaskMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton addLocalExecBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem addLocalExecTaskMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton addServerBtn = new javax.swing.JButton();
    private final javax.swing.JDialog addServerDialog = new javax.swing.JDialog();
    private final javax.swing.JMenuItem addServerMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JTextField certSerialNumberTextField = new javax.swing.JTextField();
    private final javax.swing.JFileChooser certificateFileChooser = new javax.swing.JFileChooser();
    private final javax.swing.JLabel certificateLabel = new javax.swing.JLabel();
    private final javax.swing.JButton clearQueueBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem clearQueueMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JPopupMenu commandTreeNodePopupMenu = new javax.swing.JPopupMenu();
    private final javax.swing.JMenuItem disconnectServerMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JMenuItem execRowEntryMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JMenuItem exitMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton exportQueueBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem exportQueueMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JMenu fileMenu = new javax.swing.JMenu();
    private final javax.swing.Box.Filler filler1 = new javax.swing.Box.Filler(new java.awt.Dimension(10, 0), new java.awt.Dimension(10, 0), new java.awt.Dimension(10, 32767));
    private final javax.swing.Box.Filler filler2 = new javax.swing.Box.Filler(new java.awt.Dimension(10, 0), new java.awt.Dimension(10, 0), new java.awt.Dimension(10, 32767));
    private final javax.swing.Box.Filler filler3 = new javax.swing.Box.Filler(new java.awt.Dimension(10, 0), new java.awt.Dimension(10, 0), new java.awt.Dimension(10, 32767));
    private final javax.swing.Box.Filler filler4 = new javax.swing.Box.Filler(new java.awt.Dimension(10, 0), new java.awt.Dimension(10, 0), new java.awt.Dimension(10, 32767));
    private final javax.swing.JMenu helpMenu = new javax.swing.JMenu();
    private final javax.swing.JDialog licenseDialog = new javax.swing.JDialog();
    private final javax.swing.JScrollPane licenseScrollPane = new javax.swing.JScrollPane();
    private final javax.swing.JTextArea licenseTextArea = new javax.swing.JTextArea();
    private final javax.swing.JPanel mainPanel = new javax.swing.JPanel();
    private final javax.swing.JSplitPane mainPanelSplitPane = new javax.swing.JSplitPane();
    private final javax.swing.JMenuBar menuBar = new javax.swing.JMenuBar();
    private final javax.swing.JButton moveTaskDownBtn = new javax.swing.JButton();
    private final javax.swing.JButton moveTaskUpBtn = new javax.swing.JButton();
    private final javax.swing.JButton openAndAppendFileBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem openAndAppendMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton openCertFileBtn = new javax.swing.JButton();
    private final javax.swing.JButton openFileBtn = new javax.swing.JButton();
    private final javax.swing.JFileChooser openFileChooser = new javax.swing.JFileChooser();
    private final javax.swing.JMenuItem openMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JScrollPane presenterScrollPane = new javax.swing.JScrollPane();
    private final javax.swing.JMenuItem reconnectServerMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton removeTaskFromQueueBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem removeTaskFromQueueMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JFileChooser saveAsFileChooser = new javax.swing.JFileChooser();
    private final javax.swing.JMenuItem saveAsMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton saveFileBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem saveMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JMenuItem scanNetworkMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton scanServerBtn = new javax.swing.JButton();
    private final javax.swing.JEditorPane serverAddErrorEditorPane = new javax.swing.JEditorPane();
    private final javax.swing.JScrollPane serverAddErrorScrollPane = new javax.swing.JScrollPane();
    private final javax.swing.JLabel serverAddressLabel = new javax.swing.JLabel();
    private final javax.swing.JTextField serverAddressTextField = new javax.swing.JTextField();
    private final javax.swing.JButton serverDialogCancelBtn = new javax.swing.JButton();
    private final javax.swing.JButton serverDialogConnectBtn = new javax.swing.JButton();
    private final javax.swing.JMenu serverMenu = new javax.swing.JMenu();
    private final javax.swing.JPanel serverPanel = new javax.swing.JPanel();
    private final javax.swing.JFormattedTextField serverPortFormattedTextField = new javax.swing.JFormattedTextField();
    private final javax.swing.JLabel serverPortLabel = new javax.swing.JLabel();
    private final javax.swing.JSplitPane serverSplitPane = new javax.swing.JSplitPane();
    private final javax.swing.JPopupMenu serverTreeNodePopupMenu = new javax.swing.JPopupMenu();
    private final javax.swing.JScrollPane serverTreeScrollPane = new javax.swing.JScrollPane();
    private final javax.swing.JButton showOrHideTableColumnBtn = new javax.swing.JButton();
    private final javax.swing.JButton startQueueRunBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem startQueueRunFromHereMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JMenuItem startQueueRunMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JButton stopQueueRunBtn = new javax.swing.JButton();
    private final javax.swing.JMenuItem stopQueueRunMenuItem = new javax.swing.JMenuItem();
    private final javax.swing.JPanel taskQueuePanel = new javax.swing.JPanel();
    private final javax.swing.JPopupMenu taskQueuePopupMenu = new javax.swing.JPopupMenu();
    private final javax.swing.JScrollPane taskQueueScrollPane = new javax.swing.JScrollPane();
    private final javax.swing.JMenu tasksMenu = new javax.swing.JMenu();
    private final javax.swing.JToolBar toolBar = new javax.swing.JToolBar();
    private final javax.swing.JToolBar.Separator toolBarSeparator1 = new javax.swing.JToolBar.Separator();
    private final javax.swing.JToolBar.Separator toolBarSeparator2 = new javax.swing.JToolBar.Separator();
    private final javax.swing.JButton viewLicenseBtn = new javax.swing.JButton();
    // End of variables declaration//GEN-END:variables
}
