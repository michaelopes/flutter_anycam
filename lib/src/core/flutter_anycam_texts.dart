class FlutterAnycamTexts {
  final String retry;
  final String disconnectedMessage;
  final String failureMessage;
  final String unauthorizedMessage;

  const FlutterAnycamTexts({
    this.retry = "Retry",
    this.disconnectedMessage =
        "Unable to establish connection with the camera.",
    this.failureMessage = "An error occurred connecting to the camera.",
    this.unauthorizedMessage =
        "Unauthorized connection to the camera. Please check the data provided",
  });
}
