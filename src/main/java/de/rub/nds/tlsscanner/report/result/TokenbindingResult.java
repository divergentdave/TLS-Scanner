/**
 * TLS-Scanner - A TLS Configuration Analysistool based on TLS-Attacker
 *
 * Copyright 2017-2019 Ruhr University Bochum / Hackmanit GmbH
 *
 * Licensed under Apache License 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package de.rub.nds.tlsscanner.report.result;

import de.rub.nds.tlsattacker.core.constants.ExtensionType;
import de.rub.nds.tlsattacker.core.constants.TokenBindingKeyParameters;
import de.rub.nds.tlsattacker.core.constants.TokenBindingVersion;
import de.rub.nds.tlsscanner.constants.ProbeType;
import de.rub.nds.tlsscanner.report.SiteReport;
import java.util.LinkedList;
import java.util.List;

/**
 *
 * @author robert
 */
public class TokenbindingResult extends ProbeResult {

    private List<TokenBindingVersion> supportedTokenBindingVersion = null;
    private List<TokenBindingKeyParameters> supportedTokenBindingKeyParameters = null;

    public TokenbindingResult(List<TokenBindingVersion> supportedTokenBindingVersion, List<TokenBindingKeyParameters> supportedTokenBindingKeyParameters) {
        super(ProbeType.TOKENBINDING);
        this.supportedTokenBindingVersion = supportedTokenBindingVersion;
        this.supportedTokenBindingKeyParameters = supportedTokenBindingKeyParameters;
    }

    @Override
    public void mergeData(SiteReport report) {
        report.setSupportedTokenBindingKeyParameters(supportedTokenBindingKeyParameters);
        report.setSupportedTokenBindingVersion(supportedTokenBindingVersion);
        if (supportedTokenBindingVersion != null && !supportedTokenBindingVersion.isEmpty()) {
            report.setSupportsTokenbinding(Boolean.TRUE);
            if (report.getSupportedExtensions() == null) {
                report.setSupportedExtensions(new LinkedList<ExtensionType>());
            }
            report.getSupportedExtensions().add(ExtensionType.TOKEN_BINDING);
        } else {
            report.setSupportsTokenbinding(Boolean.FALSE);
        }
    }
}
