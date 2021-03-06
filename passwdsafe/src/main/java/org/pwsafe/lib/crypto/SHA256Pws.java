/*
 * Copyright (c) 2008-2009 David Muller <roxon@users.sourceforge.net>.
 * All rights reserved. Use of the code is allowed under the
 * Artistic License 2.0 terms, as specified in the LICENSE file
 * distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
package org.pwsafe.lib.crypto;

import java.util.Locale;

import org.bouncycastle.crypto.digests.SHA256Digest;

import android.os.Build;

/**
 * SHA256 implementation. Currently uses BouncyCastle provider underneath.
 *
 * @author Glen Smith
 */
public class SHA256Pws {

    private static final boolean IS_CHROME;
    static {
        String brand = Build.BRAND.toLowerCase(Locale.getDefault());
        IS_CHROME = (brand.contains("chromium"));
    }

    public static byte[] digestN(byte[] p, int iter)
    {
        if (IS_CHROME) {
            return digestNJava(p, iter);
        } else {
            return digestNNative(p, iter);
        }
    }

    public static byte[] digest(byte[] incoming) {

    	SHA256Digest digest = new SHA256Digest();
    	byte[] output = new byte[digest.getDigestSize()];

    	digest.update(incoming, 0, incoming.length);
    	digest.doFinal(output, 0);

    	return output;

    }

    private static byte[] digestNJava(byte[] p, int iter)
    {
        SHA256Digest digest = new SHA256Digest();
        byte[] output = new byte[digest.getDigestSize()];
        byte[] input = new byte[digest.getDigestSize()];
        byte[] t;

        digest.update(p, 0, p.length);
        digest.doFinal(output, 0);

        for (int i = 0; i < iter; ++i) {
            t = input;
            input = output;
            output = t;

            digest.reset();
            digest.update(input, 0, input.length);
            digest.doFinal(output, 0);
        }

        return output;
    }

    private static native byte[] digestNNative(byte[] p, int iter);
}
