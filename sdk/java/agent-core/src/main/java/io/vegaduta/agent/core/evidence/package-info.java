/**
 * Tamper-evident audit and evidence exporters: a hash-chained (optionally signed) {@link
 * io.vegaduta.agent.api.audit.AuditSink}, chain verification, and reports that turn the audit log
 * into the record a regulator (EU AI Act Art. 9, 12, 14), an auditor (OWASP ASI coverage) or an
 * insurer (underwriting questionnaire) asks for.
 */
@Experimental
@NullMarked
package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import org.jspecify.annotations.NullMarked;
