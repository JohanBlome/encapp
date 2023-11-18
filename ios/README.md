# Encapp for iOS

This is a swift port of parts of the Android version and can be run on desktop as well as mobile targets.
A developer license and keys from Apple are needed prerequisites to build and deploy.

# 1. Prerequisites
For running encapp:

* idb connection to the device being tested (https://github.com/facebook/idb)
* If running on a mobile device, the devie needs to be unlocked and have a opened screen.
* ffmpeg with decoding support for the codecs to be tested
* install some python packages
* protobuf (https://developers.google.com/protocol-buffers/docs/downloads)


List of required python packages:
* humanfriendly
* `argparse_formatter`
* numpy
* pandas
* seaborn
* protobuf (google protocol buffers)

# 2. Operation
Follow the guidlines in the main README.

# 3. Special considerations
Since the protobuf test definitions was created with MediaCodec api in mind not all settings make sense but most will have a more or less close match.

For example the different encoder modes (cq, vbr, cbr) are not the same.
In this case vbr is the default and if available cbr can be set.
However, on iOS setting the mode is not enough (it will be very unpredictable) so a zero overshoot datarate limit with a 3 second time window wll be used.
This behavior is something that could be improved on in the future.
