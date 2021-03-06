/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.window.extensions;

import android.content.Context;

/**
 * Provider class that will instantiate the library implementation. It must be included in the
 * vendor library, and the vendor implementation must match the signature of this class.
 */
public class ExtensionProvider {
    /**
     * Provides a simple implementation of {@link ExtensionInterface} that can be replaced by
     * an OEM by overriding this method.
     */
    public static ExtensionInterface getExtensionImpl(Context context) {
        return new SampleExtensionImpl(context);
    }

    /**
     * The support library will use this method to check API version compatibility.
     * @return API version string in MAJOR.MINOR.PATCH-description format.
     */
    public static String getApiVersion() {
        return "1.0.0-settings_sample";
    }
}
