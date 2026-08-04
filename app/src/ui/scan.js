/*
 * Copyright (c) 2021-2026 GraphDefined GmbH <achim.friedland@graphdefined.com>
 * This file is part of EVSimulatorApp
 *
 * Licensed under the Affero GPL license, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// @ts-check

/**
 * Reading a code with the camera, where the platform can.
 *
 * ## What this does and does not do
 *
 * `BarcodeDetector` is a browser API, present in Android's WebView and in Chrome, and **absent in
 * WKWebView** — so on iOS this returns null and the start screen says so rather than showing a button
 * that does nothing.
 *
 * The alternative is a native scanner plugin, which is a package and a permission and a decision
 * somebody should make deliberately. It is not made here. What is here works on Android and degrades
 * to typing everywhere else, and the typed path is the same path — `sheetFor` does not know or care
 * where the string came from, which is why the whole of this application's judgement is testable
 * without a camera.
 *
 * No frame is ever stored, and nothing is decoded but the barcode itself: the stream goes from the
 * camera to the detector and stops.
 *
 * @module
 */

/**
 * @returns {null | (() => Promise<string>)} null when this platform cannot scan.
 */
export function scanner() {

    const Detector = /** @type {any} */ (globalThis).BarcodeDetector;

    if (typeof Detector !== "function") return null;
    if (typeof navigator === "undefined" || navigator.mediaDevices === undefined) return null;

    return async () => {

        const detector = new Detector({ formats: ["qr_code"] });

        const stream = await navigator.mediaDevices.getUserMedia({
            video: { facingMode: "environment" },
        });

        const video = document.createElement("video");
        video.setAttribute("playsinline", "");
        video.srcObject = stream;
        await video.play();

        try {
            // A fixed number of attempts rather than "until something appears": a scanner with no end
            // is a camera nobody turned off.
            for (let attempt = 0; attempt < 300; attempt++) {

                const codes = await detector.detect(video);
                if (codes.length > 0) return String(codes[0].rawValue ?? "");

                await new Promise(resume => setTimeout(resume, 100));
            }

            throw new Error("No code was found. Try again, or enter the address by hand.");

        } finally {
            // In a `finally`, because the one failure a user cannot diagnose is a camera light that
            // stays on.
            for (const track of stream.getTracks()) track.stop();
            video.srcObject = null;
        }
    };
}
