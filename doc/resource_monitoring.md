# Resource Monitoring in Encapp

# 1. Resource Information

This section documents all system files and data sources read by Encapp for resource monitoring.


## 1.1 GPU Information (Qualcomm-specific)

Base Directory: `/sys/class/kgsl/kgsl-3d0/`

Files Read:
1. `gpu_model`
* Contains: GPU model name
* Read: Once at startup
* Example: "Adreno 650"

2. `min_clock_mhz`
* Contains: Minimum supported clock frequency in MHz
* Read: Once at startup

3. `max_clock_mhz`
* Contains: Maximum supported clock frequency in MHz
* Read: Once at startup

4. `gpu_busy_percentage`
* Contains: Current GPU utilization as percentage
* Read: Continuously at 10 Hz (every 100ms)
* Format: Integer percentage (0-100)

5. `clock_mhz`
* Contains: Current GPU clock frequency in MHz
* Read: Continuously at 10 Hz (every 100ms)

Notes:
* Only available on Qualcomm platforms with kgsl interface
* Requires root access: `adb root`
* Requires SELinux permissive mode: `adb shell setenforce 0`


## 1.2 CPU Information

Files Read:
1. `/proc/cpuinfo`
* Contains: Hardware information for all CPU cores
* Read: Once at startup
* Fields parsed per processor:
  * `processor`: Core number/ID
  * `BogoMIPS`: Performance indicator (floating point)
  * `Features`: Supported instruction set extensions
  * `CPU implementer`: Vendor ID (hex format: 0x41 = ARM)
  * `CPU architecture`: Architecture version number
  * `CPU variant`: Variant identifier (hex)
  * `CPU part`: Part/model number (hex)
  * `CPU revision`: Revision number (hex)

2. `/sys/devices/system/cpu/cpu<N>/cpufreq/stats/time_in_state`
* Contains: Accumulated time spent at each frequency level
* Read: Continuously at 10 Hz (every 100ms) for each CPU core
* Format: Space-separated pairs per line
```
<frequency_hz> <time_in_10ms_units>
324000 5023977
610000 567359
820000 199683
...
```
  * Data is cumulative (monotonically increasing)
  * Time unit: 10 milliseconds per increment

Data Captured:
* Static: CPU identification, features, architecture details
* Dynamic: Frequency usage histograms per core


# 2. Implementation

## 2.1 SystemLoad.java

Location: `app/src/main/java/com/facebook/encapp/utils/SystemLoad.java`

This class reads and parses the different information sources above. Some of the information is read at init time, some is read periodically.

Architecture
* Sampling Thread: Dedicated background thread (`system_load_thread`) runs monitoring loop
* Sampling Rate: 10 Hz (100ms intervals): configurable via `mFrequencyHz`
* Lifecycle: Started with `start()`, stopped with `stop()`
* Graceful Degradation: Disables GPU or CPU monitoring if system files are inaccessible

Data Storage:
* `mGPUInfo`: HashMap with static GPU properties
* `mGpuLoad`: ArrayList of GPU utilization percentages (strings)
* `mGpuClock`: ArrayList of GPU clock frequencies (strings)
* `mCpuList`: ArrayList of CPUInfo objects (static data)
* `mCpuTimeInState`: ArrayList of timestamped frequency data (strings)

Data Export Methods:

1. `getCPUInfo()` (SystemLoad.java:291)
* Returns: JSONArray with CPU hardware details per core

2. `getCPUTimeInStateData()` (SystemLoad.java:309)
* Returns: JSONArray with timestamped frequency statistics
* Parses stored strings using regex: `cpu=(?<cpu>[0-9]+):ts=(?<ts>[0-9]*):data=(?<data>[0-9 \n]*)`
* Creates one JSON object per timestamp per CPU

3. `getGPUInfo()` (SystemLoad.java:266)
* Returns: HashMap with static GPU information

4. `getGPULoadPercentagePerTimeUnit()` (SystemLoad.java:239)
* Returns: int[] array of GPU utilization percentages

5. `getGPUClockFreqPerTimeUnit()` (SystemLoad.java:264)
* Returns: ArrayList of GPU clock frequencies


## 2.2 CPUInfo.java

Location: `app/src/main/java/com/facebook/encapp/utils/CPUInfo.java`

This class is just used to store the data that SystemLoad.java extracts from /proc/cpuinfo at init time.

Fields:
* `mId`: CPU core number (int)
* `mPerformance`: BogoMIPS value (float)
* `mFeatures`: Instruction set features (String)
* `mCpuImplementer`: Vendor/implementer ID (int)
* `mCpuArchitecture`: ARM architecture version (int)
* `mCpuVariant`: CPU variant number (int)
* `mCpuPart`: CPU part/model number (int)
* `mCpuRevison`: CPU revision number (int)

Usage: Instantiated once per CPU core during SystemLoad startup, stored in `mCpuList`.
