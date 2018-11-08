/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.rub.nds.tlsscanner.probe;

import de.rub.nds.modifiablevariable.util.Modifiable;
import de.rub.nds.tlsattacker.core.config.Config;
import de.rub.nds.tlsattacker.core.constants.CipherSuite;
import de.rub.nds.tlsattacker.core.constants.HandshakeMessageType;
import de.rub.nds.tlsattacker.core.protocol.message.ClientHelloMessage;
import de.rub.nds.tlsattacker.core.protocol.message.ServerHelloDoneMessage;
import de.rub.nds.tlsattacker.core.protocol.message.extension.ExtensionMessage;
import de.rub.nds.tlsattacker.core.state.State;
import de.rub.nds.tlsattacker.core.state.TlsContext;
import de.rub.nds.tlsattacker.core.workflow.ParallelExecutor;
import de.rub.nds.tlsattacker.core.workflow.WorkflowTrace;
import de.rub.nds.tlsattacker.core.workflow.WorkflowTraceUtil;
import de.rub.nds.tlsattacker.core.workflow.action.ReceiveTillAction;
import de.rub.nds.tlsattacker.core.workflow.action.SendAction;
import de.rub.nds.tlsscanner.config.ScannerConfig;
import de.rub.nds.tlsscanner.constants.ProbeType;
import de.rub.nds.tlsscanner.probe.handshakeSimulation.TlsClientConfig;
import de.rub.nds.tlsscanner.probe.handshakeSimulation.TlsClientConfigIO;
import de.rub.nds.tlsscanner.probe.handshakeSimulation.SimulatedClient;
import static de.rub.nds.tlsscanner.probe.TlsProbe.LOGGER;
import de.rub.nds.tlsscanner.report.SiteReport;
import de.rub.nds.tlsscanner.report.result.HandshakeSimulationResult;
import de.rub.nds.tlsscanner.report.result.ProbeResult;
import java.io.File;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import org.bouncycastle.crypto.tls.Certificate;
import org.bouncycastle.jce.provider.X509CertificateObject;

public class HandshakeSimulationProbe extends TlsProbe {

    private static final String RESOURCE_FOLDER = "extracted_client_configs";

    private final List<SimulatedClient> simulatedClientList;

    public HandshakeSimulationProbe(ScannerConfig config, ParallelExecutor parallelExecutor) {
        super(parallelExecutor, ProbeType.HANDSHAKE_SIMULATION, config, 1);
        simulatedClientList = new LinkedList<>();
    }

    @Override
    public ProbeResult executeTest() {
        TlsClientConfig tlsClientConfig;
        TlsClientConfigIO clientConfigIO = new TlsClientConfigIO();
        List<TlsClientConfig> tlsClientConfigList = new LinkedList<>();
        List<State> clientStateList = new LinkedList<>();
        for (File configFile : clientConfigIO.getClientConfigFileList(RESOURCE_FOLDER)) {
            tlsClientConfig = clientConfigIO.readConfigFromFile(configFile);
            tlsClientConfigList.add(tlsClientConfig);
            clientStateList.add(getPreparedClientState(tlsClientConfig));
        }
        parallelExecutor.bulkExecute(clientStateList);
        for (int i=0; i<tlsClientConfigList.size(); i++) {
            simulatedClientList.add(getSimulatedClient(tlsClientConfigList.get(i), clientStateList.get(i)));
        }
        return new HandshakeSimulationResult(simulatedClientList);
    }

    private State getPreparedClientState(TlsClientConfig clientConfig) {
        Config config = clientConfig.getConfig();
        getScannerConfig().getClientDelegate().applyDelegate(config);
        config.setQuickReceive(true);
        config.setEarlyStop(true);
        config.setStopActionsAfterFatal(true);
        config.setStopRecievingAfterFatal(true);
        ClientHelloMessage msg = new ClientHelloMessage(config);
        List<ExtensionMessage> extensions = WorkflowTraceUtil.getLastReceivedMessage(HandshakeMessageType.CLIENT_HELLO, clientConfig.getTrace()).getExtensions();
        for (ExtensionMessage extension : extensions) {
            if (extension.getExtensionBytes().getValue() != null) {
                extension.setExtensionBytes(Modifiable.explicit(extension.getExtensionBytes().getValue()));
            }
        }
        msg.setExtensions(extensions);
        WorkflowTrace trace = new WorkflowTrace();
        trace.addTlsAction(new SendAction(msg));
        trace.addTlsAction(new ReceiveTillAction(new ServerHelloDoneMessage()));
        State state = new State(config, trace);
        return state;
    }

    private SimulatedClient getSimulatedClient(TlsClientConfig tlsClientConfig, State state) {
        SimulatedClient simulatedClient = new SimulatedClient(tlsClientConfig.getType(), 
                tlsClientConfig.getVersion(), tlsClientConfig.isDefaultVersion());
        TlsContext context = state.getTlsContext();
        evaluateClientConfig(tlsClientConfig, simulatedClient);
        evaluateReceivedMessages(state, simulatedClient);
        if (simulatedClient.getReceivedServerHello()) {
            evaluateServerHello(context, simulatedClient);
        }
        if (simulatedClient.getReceivedCertificate()) {
            evaluateCertificate(context, simulatedClient);
        }
        if (simulatedClient.getReceivedServerKeyExchange()) {
            evaluateServerKeyExchange(context, simulatedClient);
        }
        if (simulatedClient.getReceivedServerHelloDone()) {
            evaluateServerHelloDone(context, simulatedClient);
        } else {
            simulatedClient.setHandshakeSuccessful(false);
            simulatedClient.setHandshakeFailedBecause("Server did not send required message: ServerHelloDone");
            if (!simulatedClient.getReceivedServerHello()) {
                simulatedClient.setHandshakeFailedBecause("Server did not send required messages: ServerHello, ServerHelloDone");
            }
        }
        return simulatedClient;
    }

    private void evaluateClientConfig(TlsClientConfig clientConfig, SimulatedClient simulatedClient) {
        simulatedClient.setSupportedRsaKeyLengthList(clientConfig.getSupportedRsaKeyLengthList());
        simulatedClient.setSupportedDheKeyLengthList(clientConfig.getSupportedDheKeyLengthList());
        simulatedClient.setHighestClientProtocolVersion(clientConfig.getConfig().getHighestProtocolVersion());
        if (clientConfig.getConfig().isAddAlpnExtension()) {
            simulatedClient.setAlpnAnnouncedProtocols(Arrays.toString(clientConfig.getConfig().getAlpnAnnouncedProtocols()));
        } else {
            simulatedClient.setAlpnAnnouncedProtocols("-");
        }
    }

    private void evaluateReceivedMessages(State state, SimulatedClient simulatedClient) {
        WorkflowTrace trace = state.getWorkflowTrace();
        simulatedClient.setReceivedServerHello(WorkflowTraceUtil.didReceiveMessage(HandshakeMessageType.SERVER_HELLO, trace));
        simulatedClient.setReceivedCertificate(WorkflowTraceUtil.didReceiveMessage(HandshakeMessageType.CERTIFICATE, trace));
        simulatedClient.setReceivedServerKeyExchange(WorkflowTraceUtil.didReceiveMessage(HandshakeMessageType.SERVER_KEY_EXCHANGE, trace));
        simulatedClient.setReceivedCertificateRequest(WorkflowTraceUtil.didReceiveMessage(HandshakeMessageType.CERTIFICATE_REQUEST, trace));
        simulatedClient.setReceivedServerHelloDone(WorkflowTraceUtil.didReceiveMessage(HandshakeMessageType.SERVER_HELLO_DONE, trace));
    }

    private void evaluateServerHello(TlsContext context, SimulatedClient simulatedClient) {
        simulatedClient.setSelectedProtocolVersion(context.getSelectedProtocolVersion());
        CipherSuite cipherSuite = context.getSelectedCipherSuite();
        simulatedClient.setSelectedCiphersuite(cipherSuite);
        if (cipherSuite.toString().contains("_DHE_") || cipherSuite.toString().contains("_ECDHE_")) {
            simulatedClient.setForwardSecrecy(true);
        } else {
            simulatedClient.setForwardSecrecy(false);
        }
        simulatedClient.setSelectedCompressionMethod(context.getSelectedCompressionMethod());
        if (context.getNegotiatedExtensionSet() != null) {
            simulatedClient.setNegotiatedExtensions(context.getNegotiatedExtensionSet().toString());
        }
    }

    private void evaluateCertificate(TlsContext context, SimulatedClient simulatedClient) {
        if (simulatedClient.getSelectedCiphersuite().name().contains("TLS_RSA")) {
            simulatedClient.setServerPublicKeyLength(getPublicKeyFromCert(context.getServerCertificate(), "TLS_RSA"));
        }
    }

    private void evaluateServerKeyExchange(TlsContext context, SimulatedClient simulatedClient) {
        CipherSuite cipherSuite = context.getSelectedCipherSuite();
        if (cipherSuite.name().contains("TLS_DH") && context.getServerDhPublicKey() != null) {
            simulatedClient.setServerPublicKeyLength(Integer.toString(context.getServerDhPublicKey().bitLength()));
        } else if (cipherSuite.name().contains("TLS_ECDH")) {
            if (context.getSelectedGroup() != null) {
                simulatedClient.setSelectedNamedGroup(context.getSelectedGroup().name());
                if (context.getSelectedGroup().getCoordinateSizeInBit() != null) {
                    simulatedClient.setServerPublicKeyLength(context.getSelectedGroup().getCoordinateSizeInBit().toString());
                }
            }
            if (simulatedClient.getServerPublicKeyLength() == null) {
                if (context.getServerEcPublicKey() != null) {
                    simulatedClient.setServerPublicKeyLength(Integer.toString(context.getServerEcPublicKey().getByteX().length * 8));
                } else {
                    simulatedClient.setServerPublicKeyLength("according to named group: " + simulatedClient.getSelectedNamedGroup());
                }
            }
        } else if (cipherSuite.name().contains("TLS_PSK") && context.getServerPSKPublicKey() != null) {
            simulatedClient.setServerPublicKeyLength(Integer.toString(context.getServerPSKPublicKey().bitLength()));
        } else if (cipherSuite.name().contains("TLS_SRP") && context.getServerSRPPublicKey() != null) {
            simulatedClient.setServerPublicKeyLength(Integer.toString(context.getServerSRPPublicKey().bitLength()));
        } else if (cipherSuite.usesGOSTR3411() && context.getServerGostEc01PublicKey() != null) {
            simulatedClient.setServerPublicKeyLength(Integer.toString(context.getServerGostEc01PublicKey().getByteX().length * 8));
        }
        if (!cipherSuite.name().contains("TLS_ECDH")) {
            simulatedClient.setSelectedNamedGroup("-");
        }
    }

    private void evaluateServerHelloDone(TlsContext context, SimulatedClient simulatedClient) {
        simulatedClient.setHandshakeSuccessful(true);
        simulatedClient.setServerPublicKeyLengthAccept(true);
        if (simulatedClient.getSelectedCiphersuite().name().contains("TLS_RSA")
                && simulatedClient.getSupportedRsaKeyLengthList() != null
                && !simulatedClient.getSupportedRsaKeyLengthList().contains(Integer.parseInt(simulatedClient.getServerPublicKeyLength()))) {
            simulatedClient.setServerPublicKeyLengthAccept(false);
        }
        if (simulatedClient.getSelectedCiphersuite().name().contains("TLS_DHE_RSA")
                && simulatedClient.getSupportedDheKeyLengthList() != null
                && !simulatedClient.getSupportedDheKeyLengthList().contains(Integer.parseInt(simulatedClient.getServerPublicKeyLength()))) {
            simulatedClient.setServerPublicKeyLengthAccept(false);
        }
        if (simulatedClient.getServerPublicKeyLengthAccept() == false) {
            simulatedClient.setHandshakeSuccessful(false);
            simulatedClient.setHandshakeFailedBecause("Server public key length ("
                    + simulatedClient.getServerPublicKeyLength() + ") probably not supported by client");
        }
    }

    private String getPublicKeyFromCert(Certificate certs, String algo) {
        try {
            if (certs != null) {
                for (org.bouncycastle.asn1.x509.Certificate cert : certs.getCertificateList()) {
                    X509Certificate x509Cert = new X509CertificateObject(cert);
                    if (x509Cert.getPublicKey() != null) {
                        if (algo.contains("RSA")) {
                            RSAPublicKey rsaPk = (RSAPublicKey) x509Cert.getPublicKey();
                            return Integer.toString(rsaPk.getModulus().bitLength());
                        }
                    }
                }
            }
        } catch (CertificateParsingException ex) {
            LOGGER.warn("Could not parse PublicKey from certificate", ex);
        }
        return null;
    }

    @Override
    public boolean shouldBeExecuted(SiteReport report) {
        return true;
    }

    @Override
    public void adjustConfig(SiteReport report) {
    }

    @Override
    public ProbeResult getNotExecutedResult() {
        return null;
    }
}