// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.steigensoft.atcamera.mlkit.textrecognition;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.text.FirebaseVisionText;
import com.google.firebase.ml.vision.text.FirebaseVisionTextRecognizer;
import com.steigensoft.atcamera.mlkit.FrameMetadata;
import com.steigensoft.atcamera.mlkit.GraphicOverlay;
import com.steigensoft.atcamera.mlkit.VisionProcessorBase;

import java.io.IOException;
import java.util.List;

import androidx.annotation.NonNull;

/**
 * Processor for the text recognition demo.
 */
public class TextRecognitionProcessor extends VisionProcessorBase<FirebaseVisionText> {

    private static final String TAG = "TextRecProc";
    private final FirebaseVisionTextRecognizer detector;

    public TextRecognitionProcessor() {
        detector = FirebaseVision.getInstance().getOnDeviceTextRecognizer();
    }


    @Override
    public void stop() {
        try {
            detector.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception thrown while trying to close Text Detector: " + e);
        }
    }

    @Override
    protected Task<FirebaseVisionText> detectInImage(FirebaseVisionImage image) {
        return detector.processImage(image);
    }

    @Override
    protected void onSuccess(
            @NonNull FirebaseVisionText results,
            @NonNull FrameMetadata frameMetadata,
            GraphicOverlay graphicOverlay, FirebaseVisionImage image) {

        //image.getBitmapForDebugging();
        if (graphicOverlay != null) {
            graphicOverlay.clear();
        }
        List<FirebaseVisionText.TextBlock> blocks = results.getTextBlocks();



        for (int i = 0; i < blocks.size(); i++) {
            if (graphicOverlay != null) {
                GraphicOverlay.Graphic textGraphic = new TextGraphic(graphicOverlay, blocks.get(i));
                graphicOverlay.add(textGraphic);
            }
        }
    }

    @Override
    protected void onFailure(@NonNull Exception e) {
        Log.w(TAG, "Text detection failed." + e);
    }
}
