/*
 * Copyright (c) 2014, Oracle America, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 *  * Neither the name of Oracle nor the names of its contributors may be used
 *    to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.example;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

import static java.lang.String.CASE_INSENSITIVE_ORDER;
import static java.util.Objects.requireNonNull;

@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 3, time = 10)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MyBenchmark {
    private static final BiPredicate<String,String> ACCEPT_ALL = (x,y) -> true;

    private static final boolean[] TCHAR = new boolean[256];
    private static final boolean[] FIELDVCHAR = new boolean[256];

    static {
        char[] lcase = ("!#$%&'*+-.^_`|~0123456789" +
            "abcdefghijklmnopqrstuvwxyz").toCharArray();
        for (char c : lcase) {
            TCHAR[c] = true;
        }
        char[] ucase = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ").toCharArray();
        for (char c : ucase) {
            TCHAR[c] = true;
        }
        for (char c = 0x21; c <= 0xFF; c++) {
            FIELDVCHAR[c] = true;
        }
        FIELDVCHAR[0x7F] = false; // a little hole (DEL) in the range
    }

    private static final List<String> RAW_HEADERS = List.of(
        "Content-Length: 0",
        "Content-MD5: zc6asiLoUz/caxlJ6YwKxA==",
        "Date: Tue, 13 Aug 2024 20:51:21 GMT",
        "ETag: \"0x8DCBBD9AFCF9366\"",
        "Last-Modified: Tue, 13 Aug 2024 20:51:21 GMT",
        "Server: Windows-Azure-Blob/1.0",
        "Server: Microsoft-HTTPAPI/2.0",
        "x-ms-client-request-id: Sanitized",
        "x-ms-content-crc64: V7knc5S52gk=",
        "x-ms-request-id: Sanitized",
        "x-ms-request-server-encrypted: true",
        "x-ms-version: 2024-08-04"
    );

    @Fork(value = 1, jvmArgs = {"-XX:+FlightRecorder", "-XX:StartFlightRecording=settings=\"D:\\GitHub\\azure-sdk-for-java\\eng\\PerfAutomation.jfc\",filename=baseline.jfr,maxsize=1gb"})
    @Benchmark
    public void java21ParsingBaseline(Blackhole blackhole) {
        // This is a demo/sample template for building your JMH benchmarks. Edit as needed.
        // Put your benchmark code here.
        Map<String, List<String>> intermediate = new HashMap<>();
        RAW_HEADERS.forEach(header -> addHeaderFromString(header, intermediate));
        blackhole.consume(mockHttpHeaders(intermediate, ACCEPT_ALL));
    }

    private static void addHeaderFromString(String headerString, Map<String, List<String>> privateMap) {
        int idx = headerString.indexOf(':');
        if (idx == -1)
            return;
        String name = headerString.substring(0, idx);

        // compatibility with HttpURLConnection;
        if (name.isEmpty()) return;

        if (!isValidName(name)) {
            throw new RuntimeException("Invalid header name \"" + name + "\"");
        }
        String value = headerString.substring(idx + 1).trim();
        if (!isValidValue(value)) {
            throw new RuntimeException("Invalid header value \"" + name + ": " + value + "\"");
        }

        privateMap.computeIfAbsent(name.toLowerCase(Locale.US),
            k -> new ArrayList<>()).add(value);
    }

    private static boolean isValidName(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c > 255 || !TCHAR[c]) {
                return false;
            }
        }
        return !token.isEmpty();
    }

    private static boolean isValidValue(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c > 255) {
                return false;
            }
            if (c == ' ' || c == '\t') {
                continue;
            } else if (!FIELDVCHAR[c]) {
                return false; // forbidden byte
            }
        }
        return true;
    }

    private static TreeMap<String, List<String>> mockHttpHeaders(Map<String, List<String>> map, BiPredicate<String, String> filter) {
        TreeMap<String,List<String>> other = new TreeMap<>(CASE_INSENSITIVE_ORDER);
        TreeSet<String> notAdded = new TreeSet<>(CASE_INSENSITIVE_ORDER);
        ArrayList<String> tempList = new ArrayList<>();
        map.forEach((key, value) -> {
            String headerName = requireNonNull(key).trim();
            if (headerName.isEmpty()) {
                throw new IllegalArgumentException("empty key");
            }
            List<String> headerValues = requireNonNull(value);
            headerValues.forEach(headerValue -> {
                headerValue = requireNonNull(headerValue).trim();
                if (filter.test(headerName, headerValue)) {
                    tempList.add(headerValue);
                }
            });

            if (tempList.isEmpty()) {
                if (other.containsKey(headerName)
                    || notAdded.contains(headerName.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("duplicate key: " + headerName);
                notAdded.add(headerName.toLowerCase(Locale.ROOT));
            } else if (other.put(headerName, List.copyOf(tempList)) != null) {
                throw new IllegalArgumentException("duplicate key: " + headerName);
            }
            tempList.clear();
        });

        return other;
    }

    // TODO: Some quick an easy performance wins:
    //  Validate the header name while lowercasing it
    //  Trim and validate the header value in the same loop
    //  Work with TreeMap directly and use a custom constructor of HttpHeaders (real case would effectively be a no-op
    //  but in this case we have a method to explain the reasoning)
    @Fork(value = 1, jvmArgs = {"-XX:+FlightRecorder", "-XX:StartFlightRecording=settings=\"D:\\GitHub\\azure-sdk-for-java\\eng\\PerfAutomation.jfc\",filename=proposal.jfr,maxsize=1gb"})
    @Benchmark
    public void proposal(Blackhole blackhole) {
        // This is a demo/sample template for building your JMH benchmarks. Edit as needed.
        // Put your benchmark code here.
        TreeMap<String, List<String>> intermediate = new TreeMap<>(CASE_INSENSITIVE_ORDER);
        RAW_HEADERS.forEach(header -> addHeaderFromStringProposal(header, intermediate));
        blackhole.consume(proposalOfInternalOptimizedHttpHeadersOf(intermediate));
    }

    private static void addHeaderFromStringProposal(String headerString, Map<String, List<String>> privateMap) {
        int idx = headerString.indexOf(':');
        if (idx == -1) {
            return;
        }

        // Use idx to determine if name will be empty.
        if (idx == 0) {
            // compatibility with HttpURLConnection;
            return;
        }

        String name = validateAndCreateName(headerString, idx);
        String value = validateAndCreateValue(headerString, idx, name);

        privateMap.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }

    // This could be inlined into addHeaderFromStringProposal but this gives us better JFR data.
    private static String validateAndCreateName(String rawHttpHeader, int colonIndex) {
        char[] chars = new char[colonIndex]; // colonIndex is the length of the name
        rawHttpHeader.getChars(0, colonIndex, chars, 0);

        // Attempt an optimization here by copying the range of characters that will become the name string into a
        // char[] (IntrinsicCandidate) and use that char[] to manage validating the name and lowercasing it.
        // name validation and lowercasing aren't IntrinsicCandidates, meaning doing both at once should be a
        // performance improvement. indexOf(int) is an IntrinsicCandidate, so continue using that to determine the
        // index of the colon.
        for (int i = 0; i < colonIndex; i++) {
            char c = chars[i];
            if (c >= 255 || !TCHAR[c]) {
                // Performance doesn't matter here as this is the exceptional case.
                throw new RuntimeException("Invalid header name \"" + rawHttpHeader.substring(0, colonIndex) + "\"");
            }

            chars[i] = Character.toLowerCase(c);
        }

        return new String(chars);
    }

    // Same reason as validateAndCreateValue
    private static String validateAndCreateValue(String rawHttpHeader, int colonIndex, String name) {
        // Similar to name validation, trim and validate the value in the same loop.
        // trim isn't an IntrinsicCandidate either and trimming should generally be an exceptional case.
        char[] chars = new char[rawHttpHeader.length() - colonIndex - 1];
        rawHttpHeader.getChars(colonIndex + 1, rawHttpHeader.length(), chars, 0);

        int len = chars.length;
        int st = 0;
        while ((st < len) && ((chars[st] & 0xff) <= ' ')) {
            st++;
        }
        while ((st < len) && ((chars[len - 1] & 0xff) <= ' ')) {
            len--;
        }

        for (int i = st; i < len; i++) {
            char c = chars[i];
            if (c >= 255 || (!FIELDVCHAR[c] && c != ' ' && c != '\t')) {
                throw new RuntimeException("Invalid header value \"" + name + ": "
                    + rawHttpHeader.substring(colonIndex + 1 + st, colonIndex + st + len) + "\"");
            }
        }

        // Use substring in this case as it should be more performant as it takes ques from the String coder whereas
        // the char[] constructor needs to do speculation.
        return rawHttpHeader.substring(colonIndex + 1 + st, colonIndex + st + len);
    }

    // This effective no-op is fine as the comments below explain why each part of the method was unnecessary when
    // interacting with it through Http1HeaderParser.
    // At a higher level, the no-op is valid as whenever Http1HeaderParser turns the intermediate
    // Map<String, List<String>> into HttpHeaders that class nulls out that reference, meaning it can never be updated
    // once turned into HttpHeaders.
    private static TreeMap<String, List<String>> proposalOfInternalOptimizedHttpHeadersOf(TreeMap<String, List<String>> map) {
//        TreeMap<String,List<String>> other = new TreeMap<>(CASE_INSENSITIVE_ORDER);
//        TreeSet<String> notAdded = new TreeSet<>(CASE_INSENSITIVE_ORDER);
//        ArrayList<String> tempList = new ArrayList<>();
//        map.forEach((key, value) -> {
            // Don't need to trim the header name as if any of the trim characters are found the header name would've
            // failed validation earlier.
            // Nor could the name be empty.
//            String headerName = requireNonNull(key).trim();
//            if (headerName.isEmpty()) {
//                throw new IllegalArgumentException("empty key");
//            }

            // Don't need to filter as the BiPredicate used from header parsing always passed true.
            // Don't need to copy the list either as the Map<String, List<String>> passed to this method is thrown
            // away once HttpHeaders.of is called.
            // Last, don't need to check nullness of values as the List<String> will be an empty string at worst.
//            List<String> headerValues = requireNonNull(value);
//            headerValues.forEach(headerValue -> {
//                headerValue = requireNonNull(headerValue).trim();
//                if (filter.test(headerName, headerValue)) {
//                    tempList.add(headerValue);
//                }
//            });

            // Due to how header parsing works, it's also impossible for there to be duplicate keys.
//            if (tempList.isEmpty()) {
//                if (other.containsKey(headerName)
//                    || notAdded.contains(headerName.toLowerCase(Locale.ROOT)))
//                    throw new IllegalArgumentException("duplicate key: " + headerName);
//                notAdded.add(headerName.toLowerCase(Locale.ROOT));
//            } else if (other.put(headerName, List.copyOf(tempList)) != null) {
//                throw new IllegalArgumentException("duplicate key: " + headerName);
//            }
//            tempList.clear();
//        });

        // So, this leaves up with a super simple no-op.
        return map;
    }

}
