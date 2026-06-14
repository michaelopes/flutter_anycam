class FlutterAnycamWebRtcIceServer {
  final String hostname;
  final String? username;
  final String? password;

  FlutterAnycamWebRtcIceServer({
    required this.hostname,
    this.username,
    this.password,
  });

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'hostname': hostname,
      'username': username,
      'password': password,
    };
  }
}
