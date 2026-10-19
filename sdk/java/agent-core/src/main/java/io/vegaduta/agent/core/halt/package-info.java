/**
 * A standing kill switch with a provable drill. {@link io.vegaduta.agent.core.halt.HaltSwitch}
 * holds the halts an overseer has engaged, {@link io.vegaduta.agent.core.halt.HaltGate} turns an
 * active halt into a {@code DENY} verdict in front of any other gate, and {@link
 * io.vegaduta.agent.core.halt.HaltDrill} demonstrates, with an audit trail, that the switch
 * actually stops a tool and that work resumes once it is released (EU AI Act Art. 14 human
 * oversight).
 */
@NullMarked
package io.vegaduta.agent.core.halt;

import org.jspecify.annotations.NullMarked;
