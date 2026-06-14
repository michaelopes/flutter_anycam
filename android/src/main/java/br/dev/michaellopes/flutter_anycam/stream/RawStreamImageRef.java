package br.dev.michaellopes.flutter_anycam.stream;

import androidx.camera.core.ImageProxy;

final class RawStreamImageRef {
    final ImageProxy image;
    final Integer customRotationDegrees;

    RawStreamImageRef(ImageProxy image, Integer customRotationDegrees) {
        this.image = image;
        this.customRotationDegrees = customRotationDegrees;
    }
}
