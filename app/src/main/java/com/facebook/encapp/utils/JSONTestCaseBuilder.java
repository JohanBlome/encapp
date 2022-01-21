package com.facebook.encapp.utils;

import android.os.Build;
import android.util.Log;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Vector;

import androidx.annotation.RequiresApi;


public class JSONTestCaseBuilder {
    private static final String TAG = "testcasebuilder";

    public static String DESCRIPTION = "description"; // Name or description of the test case
    public static String INPUT_FORMAT = "input_format"; // raw or mp4
    public static String INPUT_FPS = "input_fps";
    public static String DURATION_SEC = "duration";
    public static String LOOP = "loop";
    public static String CONCURRENT = "conc";
    public static String USE_SURFACE_ENC = "use_surface_enc";
    public static String I_INTERVALS = "i_intervals";
    public static String SKIP_FRAMES = "skip_frames";
    public static String INPUT_FILES = "input_files";
    public static String INPUT_RESOLUTION = "input_resolution";
    public static String BITRATES = "bitrates";
    public static String ENCODE_RESOLUTIONS = "encode_resolutions";
    public static String CODECS = "codecs";
    public static String FRAMERATES = "fps";
    public static String RC_MODES = "rc_modes"; // Bitrate control mode
    public static String PURSUIT = "pursuit";
    public static String REALTIME = "realtime";
    public static String CONFIGURE = "configure";
    public static String CONFIGURE_DECODER = "configure_decoder";
    public static String Encoder_RUNTIME_PARAMETERS = "runtime_parameters";
    public static String DECODER_RUNTIME_PARAMETERS = "decoder_runtime_parameters";
    public static String ENCODE = "encode";
    public static String DECODER = "decoder"; //choose specific codec for decoding


    @RequiresApi(api = Build.VERSION_CODES.O)
    public static boolean parseFile(String filename, Vector<TestParams> vcs, SessionParam session) throws IOException {
        if (vcs == null) vcs = new Vector<>();
        Path path = FileSystems.getDefault().getPath("", filename);
        Log.d(TAG, "Path: "+path.getFileName() + ", " + path.toString());
        File file = path.toFile();
        Log.d(TAG, "File exists: " + file.exists() + ", can read: " + file.canRead());
        String json = "";
        try {
            json = new String(Files.readAllBytes(path));
        } catch (Exception ex) {
            Log.d(TAG, "Failed to read test spec: " + ex.getMessage());
            return false;
        }
        JSONTokener tokenizer = new JSONTokener(json);
        Log.d(TAG, "*** Parse json ***");
        try {
            while (tokenizer.more()) {
                Object value = null;
                try {
                    value = tokenizer.nextValue();
                } catch (JSONException jex) {
                    // the json parse is very sensitive to how the file ends...
                    continue;
                }
                // Each test case is two levels deep at most
                if (value instanceof JSONObject) {
                    if (((JSONObject) value).has("tests")) {
                        JSONArray a1 = ((JSONObject) value).getJSONArray("tests");
                        for (int i = 0; i < a1.length(); i++) {
                            Object o1 = a1.get(i);
                            if (o1 instanceof JSONArray) {
                                JSONArray a2 = (JSONArray) o1;
                                Log.w(TAG, "Unsupported structure: " + a2.toString());
                            } else if (o1 instanceof JSONObject) {
                                ParseTest(vcs, session, (JSONObject) o1);
                            }
                        }
                    } else {
                        ParseTest(vcs, session, (JSONObject) value);
                    }
                } else {
                    Log.d(TAG, "Error should be a jsons array or json object at the top level");
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            Log.e(TAG, e.getLocalizedMessage());
        } catch (Exception ex) {
            Log.e(TAG, ex.getLocalizedMessage());
        }
        return true;
    }

    protected static void ParseTest(Vector<TestParams> vcs, SessionParam session, JSONObject o1) throws JSONException {
        String[] bitrates = {"1000k"};
        String[] encode_resolutions = {"1280x720"};
        String[] codecs = {"OMX.google.h264.encoder"};
        String[] fps = {"30"};
        String[] mod = {"CBR"};
        String[] input_files = {};
        String[] i_intervals = {"10"};
        ArrayList<ConfigureParam> config_encoder = new ArrayList<>();
        ArrayList<ConfigureParam> config_decoder = new ArrayList<>();
        String description = "";
        String input_format = "";
        String input_resolution = "1280x720";
        String duration_sec = "-1";
        String enc_loop = "0";
        String conc = "0";
        String use_surface_enc = "false";
        String input_fps = "30";
        String skip_frames = "false";
        String pursuit = "0";
        String realtime = "false";
        String encode = "true";
        String decoder = "";

        ArrayList<Object> encoder_runtime_parameters = new ArrayList<>();
        ArrayList<Object> decoder_runtime_parameters = new ArrayList<>();

        JSONObject test = o1;
        Log.d(TAG, "\n\n test: " + test + " new test collection");
        for (Iterator<String> it = test.keys(); it.hasNext(); ) {
            String case_key = it.next();

            Object data_object = test.get(case_key);
            Log.d(TAG, "Key  = " + case_key  + " - " + data_object);

            if (case_key.equals(DESCRIPTION)) {
                description = test.getString(case_key);
            } else if (case_key.equals(INPUT_FORMAT)) {
                input_format = test.getString(case_key);
            } else if (case_key.equals(INPUT_RESOLUTION)) {
                input_resolution = test.getString(case_key);
            } else if (case_key.equals(DURATION_SEC)) {
                duration_sec = test.getString(case_key);
            } else if (case_key.equals(LOOP)) {
                enc_loop = test.getString(case_key);
            } else if (case_key.equals(CONCURRENT)) {
                conc = test.getString(case_key);
            } else if (case_key.equals(USE_SURFACE_ENC)) {
                use_surface_enc = test.getString(case_key);
            } else if (case_key.equals(INPUT_FPS)) {
                input_fps = test.getString(case_key);
            } else if (case_key.equals(I_INTERVALS)) {
                i_intervals = getStringArray((JSONArray) data_object);
            } else if (case_key.equals(SKIP_FRAMES)) {
                skip_frames = test.getString(case_key);
            } else if (case_key.equals(INPUT_FILES)) {
                input_files = getStringArray((JSONArray) data_object);
            } else if (case_key.equals(BITRATES)) {
                bitrates = getStringArray((JSONArray) data_object);
            } else if (case_key.equals(ENCODE_RESOLUTIONS)) {
                encode_resolutions = getStringArray((JSONArray) data_object);
            } else if (case_key.equals(CODECS)) {
                codecs = getStringArray((JSONArray) data_object);
            } else if (case_key.equals(FRAMERATES)) {
                fps = getStringArray((JSONArray) data_object);
            } else if (case_key.equals(RC_MODES)) {
                mod = getStringArray((JSONArray) data_object);
            } else if (case_key.equals(PURSUIT)) {
                pursuit = test.getString(case_key);
            } else if (case_key.equals(REALTIME)) {
                realtime = test.getString(case_key);
            } else if (case_key.equals(ENCODE)) {
                encode = test.getString(case_key);
            } else if (case_key.equals(DECODER)) {
                decoder = test.getString(case_key);
            } else if (case_key.equals(CONFIGURE)) {
                JSONArray config_array = (JSONArray) data_object;
            /*
            "configure": [{
                "name": "tl-schema",
                "type": "string",
                "setting" : "android.generic.2"
            }],
            */
                Log.d(TAG, "Configure encoder: " + config_array.toString());
                for (int pnum = 0; pnum < config_array.length(); pnum++) {
                    JSONObject param = config_array.getJSONObject(pnum);
                    String type = param.getString("type");
                    Log.d(TAG, "type = "+type);
                    if (type.toLowerCase(Locale.US).equals("int")) {
                        config_encoder.add(new ConfigureParam(param.getString("name"), param.getInt("setting")));
                    } else if (type.toLowerCase(Locale.US).equals("float") | type.toLowerCase(Locale.US).equals("double")) {
                        config_encoder.add(new ConfigureParam(param.getString("name"), param.getDouble("setting")));
                    } else if (type.toLowerCase(Locale.US).equals("string")) {
                        config_encoder.add(new ConfigureParam(param.getString("name"), param.getString("setting")));
                    }
                }

            }  else if (case_key.equals(CONFIGURE_DECODER)) {
                JSONArray config_array = (JSONArray) data_object;
            /*
            "configure": [{
                "name": "tl-schema",
                "type": "string",
                "setting" : "android.generic.2"
            }],
            */
                Log.d(TAG, "Configure decoder: " + config_array.toString());
                for (int pnum = 0; pnum < config_array.length(); pnum++) {
                    JSONObject param = config_array.getJSONObject(pnum);
                    String type = param.getString("type");
                    Log.d(TAG, "type = "+type);
                    if (type.toLowerCase(Locale.US).equals("int")) {
                        config_decoder.add(new ConfigureParam(param.getString("name"), param.getInt("setting")));
                    } else if (type.toLowerCase(Locale.US).equals("float") | type.toLowerCase(Locale.US).equals("double")) {
                        config_decoder.add(new ConfigureParam(param.getString("name"), param.getDouble("setting")));
                    } else if (type.toLowerCase(Locale.US).equals("string")) {
                        config_decoder.add(new ConfigureParam(param.getString("name"), param.getString("setting")));
                    }
                }

            } else if (case_key.equals(Encoder_RUNTIME_PARAMETERS)) {
            /*
             "runtime_parameters": [
                {
                    "name": "vendor.qti-ext-enc-ltr.mark-frame",
                    "type" : "int",
                    "settings": [
                        "10",
                        "20"
                    ]
                },
                {
                    "name": "vendor.qti-ext-enc-ltr.use-frame" ,
                    "type": "int",
                    "settings": [
                        {"40" : "10"},
                        {"60" : "20"}
                    ]
                }
            ],
             */
                JSONArray runtime_array = (JSONArray) data_object;
                for (int rp = 0; rp < runtime_array.length(); rp++) {
                    JSONObject param = runtime_array.getJSONObject(rp);
                    parseSetting(param, encoder_runtime_parameters);
                }
            } else if (case_key.equals(DECODER_RUNTIME_PARAMETERS)) {
            /*
             "runtime_parameters": [
                {
                    "name": "vendor.qti-ext-enc-ltr.mark-frame",
                    "type" : "int",
                    "settings": [
                        "10",
                        "20"
                    ]
                },
                {
                    "name": "vendor.qti-ext-enc-ltr.use-frame" ,
                    "type": "int",
                    "settings": [
                        {"40" : "10"},
                        {"60" : "20"}
                    ]
                }
            ],
             */
                JSONArray runtime_array = (JSONArray) data_object;
                for (int rp = 0; rp < runtime_array.length(); rp++) {
                    JSONObject param = runtime_array.getJSONObject(rp);
                    parseSetting(param, decoder_runtime_parameters);
                }
            }
        }

        if (session.getInputFile() != null) {
            input_files = new String[]{session.getInputFile()};
        }
        if (session.getInputFps() != null) {
            input_fps = session.getInputFps();
        }

        if (session.getOutputFps() != null) {
            fps = new String[]{session.getOutputFps()};
        }

        if (session.getInputResolution() != null) {
            input_resolution = session.getInputResolution();
        }
        if (session.getOutputCodec() != null) {
            codecs = new String[]{session.getOutputCodec()};
        }
        if (session.getOutputResolution() != null) {
            encode_resolutions = new String[]{session.getOutputResolution()};
        }
        int index = 0;
        Vector<TestParams> vc = new Vector<>();
        for (int iF = 0; iF < input_files.length; iF++) {
            for (int eC = 0; eC < codecs.length; eC++) {
                for (int mC = 0; mC < mod.length; mC++) {
                    for (int vC = 0; vC < encode_resolutions.length; vC++) {
                        for (int fC = 0; fC < fps.length; fC++) {
                            for (int bC = 0; bC < bitrates.length; bC++) {
                                for (int kC = 0; kC < i_intervals.length; kC++) {
                                    TestParams testParams = new TestParams();
                                    Size videoSize = SizeUtils.parseXString(encode_resolutions[vC]);
                                    testParams.setVideoSize(videoSize);
                                    if (bitrates[bC].endsWith("k")) {
                                        testParams.setBitRate(Math.round(Float.parseFloat(
                                                bitrates[bC].substring(0, bitrates[bC].lastIndexOf('k'))) * 1000));
                                    } else if (bitrates[bC].endsWith("M")) {
                                        testParams.setBitRate(Math.round(Float.parseFloat(
                                                bitrates[bC].substring(0, bitrates[bC].lastIndexOf('M'))) * 1000000));
                                    } else {
                                        testParams.setBitRate(Math.round(Float.parseFloat(bitrates[bC])));
                                    }
                                    testParams.setBitrateMode(mod[mC]);
                                    testParams.setKeyframeInterval(Integer.parseInt(i_intervals[kC]));
                                    testParams.setFPS(Integer.parseInt(fps[fC]));
                                    testParams.setReferenceFPS(Float.parseFloat(input_fps));
                                    testParams.setCodecName(codecs[eC]);
                                    testParams.setBitrateMode(mod[mC].toLowerCase());
                                    testParams.setSkipFrames(Boolean.parseBoolean(skip_frames));
                                    testParams.setInputfile(input_files[iF]);
                                    testParams.setEncoderRuntimeParameters(encoder_runtime_parameters);
                                    testParams.setDecoderRuntimeParameters(encoder_runtime_parameters);
                                    testParams.setEncoderConfigure(config_encoder);
                                    testParams.setDecoderConfigure(config_decoder);
                                    testParams.setReferenceSize(SizeUtils.parseXString(input_resolution));
                                    testParams.setLoopCount(Integer.parseInt(enc_loop));
                                    testParams.setConcurrentCodings(Integer.parseInt(conc));
                                    testParams.setDescription(description);
                                    testParams.setPursuit(Integer.parseInt(pursuit));
                                    testParams.setRealtime(Boolean.parseBoolean(realtime));
                                    if (!Boolean.parseBoolean(encode)) testParams.setNoEncoding(true);
                                    testParams.setDecoder(decoder);
                                    testParams.setDurationSec(Integer.parseInt(duration_sec));
                                    vc.add(testParams);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (vc.size() == 0) {
            Log.e(TAG, "No test created");
            Log.e(TAG, "encoders: " + codecs.length);
            Log.e(TAG, "mod: " + mod.length);
            Log.e(TAG, "resolutions: " + encode_resolutions.length);
            Log.e(TAG, "fps: " + fps.length);
            Log.e(TAG, "bitrates: " + bitrates.length);
            Log.e(TAG, "i frame intervals: " + i_intervals.length);
        } else {
            Log.d(TAG, vc.size() + " nbr of test in " + description);
            for (TestParams vc_ : vcs) {
                Log.d(TAG, vc_.getSettings());
            }
            Log.d(TAG, "Add " + vc.size() + " number of test (" + description + ")");
            vcs.addAll(vc);
        }
    }

    protected static void parseSetting(JSONObject param, ArrayList<Object> parameters) throws JSONException {
        String name = param.getString("name").trim();
        String type = param.getString("type").trim();
        JSONArray settings = param.getJSONArray("settings");
        ArrayList<Object> list = new ArrayList<>(); // for bundles, if available
        for (int k = 0; k < settings.length(); k++) {
            Object val = settings.get(k);
            if (val instanceof JSONObject) {
                if (type.toLowerCase(Locale.US).equals("int")) {
                    //Should be only a pair i.e. {frame, "value"}
                    JSONArray ja = ((JSONObject) val).names();
                    int frame = ja.getInt(0);
                    Object rt_data = null;
                    rt_data = ((JSONObject) val).get(String.valueOf(frame));
                    parameters.add(new RuntimeParam(name, frame, type, rt_data));
                } else if (type.toLowerCase(Locale.US).equals("float")) {
                    //Should be only a pair i.e. {frame, "value"}
                    JSONArray ja = ((JSONObject) val).names();
                    int frame = ja.getInt(0);
                    Object rt_data = null;
                    rt_data = ((JSONObject) val).get(String.valueOf(frame));
                    parameters.add(new RuntimeParam(name, frame, type, rt_data));
                } else if (type.toLowerCase(Locale.US).equals("bundle")) {
                    JSONObject loc_val = ((JSONObject) val);
                    parseSetting((JSONObject)loc_val, list);
                }  else {
                    Log.e(TAG, "Unknown dynamic type: " + type);
                    break;
                }
            } else if (type.equals("string")) {
                int frame = Integer.parseInt(val.toString());
                parameters.add(new RuntimeParam(name, frame, type, null));
            } else if (type.equals("int")) {
                int frame = Integer.parseInt(val.toString());
                parameters.add(new RuntimeParam(name, frame, type, Integer.valueOf(val.toString())));
            }
        }

        if (list.size() > 0) {
            parameters.add(new RuntimeParam(name, -1, type, list));
        }
        Log.d(TAG, "RT done");
    }

    public static String[] getStringArray(JSONArray array) {
        String[] data = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            try {
                data[i] = array.getString(i);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return data;
    }

}
