package com.agenticai.core.common.studioapp;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

/** Looks up TXT records. Injectable so verification logic is testable without DNS. */
public interface DnsTxtResolver {

    /** TXT values at {@code name}; empty when the name has none; {@code null} when the lookup failed. */
    List<String> lookupTxt(String name);

    /** JNDI DNS provider (system resolver) with a hard timeout. */
    class Jndi implements DnsTxtResolver {

        private final int timeoutMillis;

        public Jndi(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public List<String> lookupTxt(String name) {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(timeoutMillis));
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            InitialDirContext ctx = null;
            try {
                ctx = new InitialDirContext(env);
                Attributes attrs = ctx.getAttributes(name, new String[] {"TXT"});
                Attribute txt = attrs.get("TXT");
                List<String> out = new ArrayList<>();
                if (txt != null) {
                    NamingEnumeration<?> all = txt.getAll();
                    while (all.hasMore()) {
                        out.add(String.valueOf(all.next()));
                    }
                }
                return out;
            } catch (NameNotFoundException e) {
                return List.of();
            } catch (Exception e) {
                return null;
            } finally {
                if (ctx != null) {
                    try {
                        ctx.close();
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            }
        }
    }
}
