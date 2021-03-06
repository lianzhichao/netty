/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.resolver.dns;

import io.netty.util.NetUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.netty.resolver.dns.DnsServerAddresses.DNS_PORT;
import static io.netty.util.internal.StringUtil.indexOfNonWhiteSpace;

/**
 * Able to parse files such as <a href="https://linux.die.net/man/5/resolver">/etc/resolv.conf</a> and
 * <a href="https://developer.apple.com/legacy/library/documentation/Darwin/Reference/ManPages/man5/resolver.5.html">
 * /etc/resolver</a> to respect the system default domain servers.
 */
@UnstableApi
public final class UnixResolverDnsServerAddressStreamProvider implements DnsServerAddressStreamProvider {
    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(UnixResolverDnsServerAddressStreamProvider.class);
    private static final String NAMESERVER_ROW_LABEL = "nameserver";
    private static final String SORTLIST_ROW_LABEL = "sortlist";
    private static final String DOMAIN_ROW_LABEL = "domain";
    private static final String PORT_ROW_LABEL = "port";
    private final DnsServerAddresses defaultNameServerAddresses;
    private final Map<String, DnsServerAddresses> domainToNameServerStreamMap;

    /**
     * Attempt to parse {@code /etc/resolv.conf} and files in the {@code /etc/resolver} directory by default.
     * A failure to parse will return {@link NoopDnsServerAddressStreamProvider}.
     */
    public static DnsServerAddressStreamProvider parseSilently() {
        try {
            UnixResolverDnsServerAddressStreamProvider nameServerCache =
                    new UnixResolverDnsServerAddressStreamProvider("/etc/resolv.conf", "/etc/resolver");
            return nameServerCache.mayOverrideNameServers() ? nameServerCache
                                                            : NoopDnsServerAddressStreamProvider.INSTANCE;
        } catch (Exception e) {
            logger.debug("failed to parse /etc/resolv.conf and/or /etc/resolver", e);
            return NoopDnsServerAddressStreamProvider.INSTANCE;
        }
    }

    /**
     * Parse a file of the format <a href="https://linux.die.net/man/5/resolver">/etc/resolv.conf</a> which may contain
     * the default DNS server to use, and also overrides for individual domains. Also parse list of files of the format
     * <a href="
     * https://developer.apple.com/legacy/library/documentation/Darwin/Reference/ManPages/man5/resolver.5.html">
     * /etc/resolver</a> which may contain multiple files to override the name servers used for multimple domains.
     * @param etcResolvConf <a href="https://linux.die.net/man/5/resolver">/etc/resolv.conf</a>.
     * @param etcResolverFiles List of files of the format defined in
     * <a href="
     * https://developer.apple.com/legacy/library/documentation/Darwin/Reference/ManPages/man5/resolver.5.html">
     * /etc/resolver</a>.
     * @throws IOException If an error occurs while parsing the input files.
     */
    public UnixResolverDnsServerAddressStreamProvider(File etcResolvConf, File... etcResolverFiles) throws IOException {
        if (etcResolvConf == null && (etcResolverFiles == null || etcResolverFiles.length == 0)) {
            throw new IllegalArgumentException("no files to parse");
        }
        if (etcResolverFiles != null) {
            domainToNameServerStreamMap = parse(etcResolverFiles);
            if (etcResolvConf != null) {
                Map<String, DnsServerAddresses> etcResolvConfMap = parse(etcResolvConf);
                defaultNameServerAddresses = etcResolvConfMap.remove(etcResolvConf.getName());
                domainToNameServerStreamMap.putAll(etcResolvConfMap);
            } else {
                defaultNameServerAddresses = null;
            }
        } else {
            domainToNameServerStreamMap = parse(etcResolvConf);
            defaultNameServerAddresses = domainToNameServerStreamMap.remove(etcResolvConf.getName());
        }
    }

    /**
     * Parse a file of the format <a href="https://linux.die.net/man/5/resolver">/etc/resolv.conf</a> which may contain
     * the default DNS server to use, and also overrides for individual domains. Also parse a directory of the format
     * <a href="
     * https://developer.apple.com/legacy/library/documentation/Darwin/Reference/ManPages/man5/resolver.5.html">
     * /etc/resolver</a> which may contain multiple files to override the name servers used for multimple domains.
     * @param etcResolvConf <a href="https://linux.die.net/man/5/resolver">/etc/resolv.conf</a>.
     * @param etcResolverDir Directory containing files of the format defined in
     * <a href="
     * https://developer.apple.com/legacy/library/documentation/Darwin/Reference/ManPages/man5/resolver.5.html">
     * /etc/resolver</a>.
     * @throws IOException If an error occurs while parsing the input files.
     */
    public UnixResolverDnsServerAddressStreamProvider(String etcResolvConf, String etcResolverDir) throws IOException {
        this(etcResolvConf == null ? null : new File(etcResolvConf),
             etcResolverDir == null ? null :new File(etcResolverDir).listFiles());
    }

    @Override
    public DnsServerAddressStream nameServerAddressStream(String hostname) {
        for (;;) {
            int i = hostname.indexOf('.', 1);
            if (i < 0 || i == hostname.length() - 1) {
                return defaultNameServerAddresses != null ? defaultNameServerAddresses.stream() : null;
            }

            DnsServerAddresses addresses = domainToNameServerStreamMap.get(hostname);
            if (addresses != null) {
                return addresses.stream();
            }

            hostname = hostname.substring(i + 1);
        }
    }

    boolean mayOverrideNameServers() {
        return !domainToNameServerStreamMap.isEmpty() ||
                defaultNameServerAddresses != null && defaultNameServerAddresses.stream().next() != null;
    }

    private static Map<String, DnsServerAddresses> parse(File... etcResolverFiles) throws IOException {
        Map<String, DnsServerAddresses> domainToNameServerStreamMap =
                new HashMap<String, DnsServerAddresses>(etcResolverFiles.length << 1);
        for (File etcResolverFile : etcResolverFiles) {
            if (!etcResolverFile.isFile()) {
                continue;
            }
            FileReader fr = new FileReader(etcResolverFile);
            BufferedReader br = null;
            try {
                br = new BufferedReader(fr);
                List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>(2);
                String domainName = etcResolverFile.getName();
                int port = DNS_PORT;
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    char c;
                    if (line.isEmpty() || (c = line.charAt(0)) == '#' || c == ';') {
                        continue;
                    }
                    if (line.startsWith(NAMESERVER_ROW_LABEL)) {
                        int i = indexOfNonWhiteSpace(line, NAMESERVER_ROW_LABEL.length());
                        if (i < 0) {
                            throw new IllegalArgumentException("error parsing label " + NAMESERVER_ROW_LABEL +
                                    " in file " + etcResolverFile + ". value: " + line);
                        }
                        String maybeIP = line.substring(i);
                        // There may be a port appended onto the IP address so we attempt to extract it.
                        if (!NetUtil.isValidIpV4Address(maybeIP) && !NetUtil.isValidIpV6Address(maybeIP)) {
                            i = maybeIP.lastIndexOf('.');
                            if (i + 1 >= maybeIP.length()) {
                                throw new IllegalArgumentException("error parsing label " + NAMESERVER_ROW_LABEL +
                                        " in file " + etcResolverFile + ". invalid IP value: " + line);
                            }
                            port = Integer.parseInt(maybeIP.substring(i + 1));
                            maybeIP = maybeIP.substring(0, i);
                        }
                        addresses.add(new InetSocketAddress(SocketUtils.addressByName(maybeIP), port));
                    } else if (line.startsWith(DOMAIN_ROW_LABEL)) {
                        int i = indexOfNonWhiteSpace(line, DOMAIN_ROW_LABEL.length());
                        if (i < 0) {
                            throw new IllegalArgumentException("error parsing label " + DOMAIN_ROW_LABEL +
                                    " in file " + etcResolverFile + " value: " + line);
                        }
                        domainName = line.substring(i);
                        if (addresses != null && !addresses.isEmpty()) {
                            putIfAbsent(domainToNameServerStreamMap, domainName, addresses);
                        }
                        addresses = new ArrayList<InetSocketAddress>(2);
                    } else if (line.startsWith(PORT_ROW_LABEL)) {
                        int i = indexOfNonWhiteSpace(line, PORT_ROW_LABEL.length());
                        if (i < 0) {
                            throw new IllegalArgumentException("error parsing label " + PORT_ROW_LABEL +
                                    " in file " + etcResolverFile + " value: " + line);
                        }
                        port = Integer.parseInt(line.substring(i));
                    } else if (line.startsWith(SORTLIST_ROW_LABEL)) {
                        logger.info("row type {} not supported. ignoring line: {}", SORTLIST_ROW_LABEL, line);
                    }
                }
                if (addresses != null && !addresses.isEmpty()) {
                    putIfAbsent(domainToNameServerStreamMap, domainName, addresses);
                }
            } finally {
                if (br == null) {
                    fr.close();
                } else {
                    br.close();
                }
            }
        }
        return domainToNameServerStreamMap;
    }

    private static void putIfAbsent(Map<String, DnsServerAddresses> domainToNameServerStreamMap,
                                    String domainName,
                                    List<InetSocketAddress> addresses) {
        // TODO(scott): sortlist is being ignored.
        putIfAbsent(domainToNameServerStreamMap, domainName, DnsServerAddresses.shuffled(addresses));
    }

    private static void putIfAbsent(Map<String, DnsServerAddresses> domainToNameServerStreamMap,
                                    String domainName,
                                    DnsServerAddresses addresses) {
        DnsServerAddresses existingAddresses = domainToNameServerStreamMap.put(domainName, addresses);
        if (existingAddresses != null) {
            domainToNameServerStreamMap.put(domainName, existingAddresses);
            logger.debug("Domain name {} already maps to addresses {} so new addresses {} will be discarded",
                    domainName, existingAddresses, addresses);
        }
    }
}
