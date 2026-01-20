# Steps to Perform Battery Test

# 1. Enable required flags

Add or update the following fields in the pbtxt configuration:

```plaintext
nullEncode: true
battery_test: true
```

**Explanation:**
- `nullEncode: true`
    - Enables null encoding set to true if required.
- `battery_test: true`
    - Enables battery test mode.

# 2. Save and apply the configuration

Save the updated pbtxt file and restart the application so the changes take effect.

# 3. Start the battery test

Launch the application and initiate the battery test workflow.

Example:
```bash
python encapp.py run <filename.pbtxt> 
```
Replace `<filename.pbtxt>` with your pbtxt file.

# 4. Disconnect the device when prompted

When the following message appears on the screen:

> "Disconnect the device for battery test"

**Action required:**
- Disconnect the device (USB / power cable as applicable).
- Keep the device disconnected for the entire duration of the battery test.

⚠️ **Note:**
- Once the message is displayed user has 30 seconds to disconnect.
- Do not reconnect the device until the test is completed.

# 5. Complete the test

Allow the battery test to finish and ensure it reaches a completed state.

# 6. Pull battery test results

After completion, pull the result files using this command:
```bash
python encapp.py --serial <device_serial> pull_result
```
Replace `<device_serial>` with your actual device serial number.