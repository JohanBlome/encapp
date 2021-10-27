package com.facebook.encapp.utils;

import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Vector;


public class JSONTestCaseBuilder {
    private static final String TAG = "testcasebuilder";

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static Vector<TestParams> parseFile(String filename) throws IOException {
        Vector<TestParams> vcs = new Vector<TestParams>();
        Path path = FileSystems.getDefault().getPath("", filename);
        Log.d(TAG, "Path: "+path.getFileName());
        String json = new String(Files.readAllBytes(path));
        JSONTokener tokenizer = new JSONTokener(json);

        // Some toplevel settings
        String[] topFiles = {};
        try {
            while (tokenizer.more()) {
                Object value = tokenizer.nextValue();
                // Each test case is two levels deep at most
                if (value instanceof JSONArray) {
                    JSONArray a1 = (JSONArray) value;
                    for (int i = 0; i < a1.length(); i++) {
                        Object o1 = a1.get(i);
                        if (o1 instanceof JSONArray) {
                            JSONArray a2 = (JSONArray)o1;
                            Log.w(TAG, "Unsupported structure: " + a2.toString());
                        } else if (o1 instanceof JSONObject) {
                            JSONObject j1 = (JSONObject)o1;
                            String[] bitrates = {"1000"};
                            String[] encode_resolutions = {"1280x720"};
                            String[] codecs = {"hevc"};
                            String[] fps = {"30"};
                            String[] mod = {"VBR"};
                            String[] input_files = {};
                            String[] i_frame_sizes = {"default"};
                            String[] i_intervals = {"10"};
                            ArrayList<ConfigureParam> config_extra = new ArrayList<>();
                            String description = "";
                            String input_format = "";
                            String input_resolution = "1280x720";
                            String duration = "10";
                            String enc_loop = "0";
                            String use_surface_enc = "false";
                            String input_fps = "30";
                            String skip_frames = "false";
                            ArrayList<RuntimeParam> runtime_parameters = new ArrayList<>();

                            for (Iterator<String> it = j1.keys(); it.hasNext(); ) {
                                String key = it.next();
                                Object o2 = j1.get(key);
                                Log.d(TAG, "Iterate jsonobject, key  " + key + " - " + o2);
                                if ( key.equals("description") ) {
                                    description = j1.getString(key);
                                } else if ( key.equals("input_format") ) {
                                    input_format = j1.getString(key);
                                } else if ( key.equals("input_resolution") ) {
                                    input_resolution = j1.getString(key);
                                } else if ( key.equals("duration") ) {
                                    duration = j1.getString(key);
                                } else if ( key.equals("enc_loop") ) {
                                    enc_loop = j1.getString(key);
                                } else if ( key.equals("use_surface_enc") ) {
                                    use_surface_enc = j1.getString(key);
                                } else if ( key.equals("input_fps") ) {
                                    input_fps = j1.getString(key);
                                } else if ( key.equals("i_intervals") ) {
                                    i_intervals = getStringArray((JSONArray)o2);
                                } else if ( key.equals("i_frame_sizes") ) {
                                    i_frame_sizes = getStringArray((JSONArray)o2);
                                } else if ( key.equals("skip_frames") ) {
                                    skip_frames = j1.getString(key);
                                } else if ( key.equals("input_files") ) {
                                    input_files = getStringArray((JSONArray)o2);
                                }  else if ( key.equals("bitrates") ) {
                                    bitrates = getStringArray((JSONArray)o2);
                                } else if ( key.equals("encode_resolutions") ) {
                                    encode_resolutions = getStringArray((JSONArray)o2);
                                } else if ( key.equals("codecs") ) {
                                    codecs = getStringArray((JSONArray)o2);
                                } else if ( key.equals("fps") ) {
                                    fps = getStringArray((JSONArray)o2);
                                } else if ( key.equals("mod") ) {
                                    mod = getStringArray((JSONArray)o2);
                                } else if ( key.equals("configure") ) {
                                    JSONArray a2 = (JSONArray)o2;
                                /*
                                "configure": [{
                                    "name": "tl-schema",
                                    "type": "string",
                                    "setting" : "android.generic.2"
                                }],
                                */

                                    for (int j = 0; j < a2.length(); j++) {
                                        JSONObject j2 = a2.getJSONObject(j);
                                        String type = j2.getString("type");
                                        if (type.toLowerCase(Locale.US).equals("int")) {
                                            config_extra.add(new ConfigureParam(j2.getString("name"), j2.getInt("setting")));
                                        } else if (type.toLowerCase(Locale.US).equals("string")) {
                                            config_extra.add(new ConfigureParam(j2.getString("name"), j2.getString("setting")));
                                        }
                                    }

                                } else if (key.equals("runtime_parameters")) {
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
                                    JSONArray a2 = (JSONArray)o2;

                                    for (int j = 0; j < a2.length(); j++) {
                                        Object obj = a2.get(j);
                                        if (obj instanceof JSONObject) {
                                            JSONObject j2 = (JSONObject) obj;

                                            String name = j2.getString("name");
                                            String type = j2.getString("type");
                                            JSONArray settings = j2.getJSONArray("settings");
                                            for (int k = 0; k < settings.length(); k++) {
                                                Object val = settings.get(k);
                                                if (val instanceof String) {
                                                    int frame = Integer.parseInt(val.toString());
                                                    runtime_parameters.add(new RuntimeParam(name, frame, null));
                                                } else if (val instanceof JSONObject) {
                                                    //Should be only a pair i.e. {frame, "value"}
                                                    JSONArray ja = ((JSONObject) val).names();
                                                    int frame = ja.getInt(0);
                                                    Object data = null;
                                                    if (type.toLowerCase(Locale.US).equals("int")) {
                                                        data = ((JSONObject) val).getInt(Integer.toString(frame));
                                                    } else {
                                                        Log.e(TAG, "Unknown dynamic type: " + type);
                                                        break;
                                                    }
                                                    runtime_parameters.add(new RuntimeParam(name, frame, data));

                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (input_files.length == 0) {
                                input_files = topFiles;
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
                                                        for (int iS = 0; iS < i_frame_sizes.length; iS++) {
                                                            TestParams testParams = new TestParams();
                                                            Size videoSize = SizeUtils.parseXString(encode_resolutions[vC]);
                                                            testParams.setVideoSize(videoSize);
                                                            testParams.setBitRate(Math.round(Float.parseFloat(bitrates[bC]) * 1000));
                                                            testParams.setKeyframeInterval(Integer.parseInt(i_intervals[kC]));
                                                            testParams.setFPS(Integer.parseInt(fps[fC]));
                                                            testParams.setReferenceFPS(Integer.parseInt(input_fps));
                                                            testParams.setVideoEncoderIdentifier(codecs[eC]);
                                                            testParams.setConstantBitrate((mod[mC].toLowerCase(Locale.US).equals("cbr")));
                                                            testParams.setIframeSizePreset(TestParams.IFRAME_SIZE_PRESETS.valueOf(i_frame_sizes[iS].toUpperCase(Locale.US)));
                                                            testParams.setSkipFrames(Boolean.parseBoolean(skip_frames));
                                                            testParams.setInputfile(input_files[iF]);
                                                            testParams.setRuntimeParameters(runtime_parameters);
                                                            testParams.setExtraConfigure(config_extra);
                                                            testParams.setReferenceSize(SizeUtils.parseXString(input_resolution));
                                                            vc.add(testParams);

                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (vc.size() == 0) {
                                Log.d(TAG, "No test created");
                                Log.d(TAG, "encoders: " + codecs.length);
                                Log.d(TAG, "mod: " + mod.length);
                                Log.d(TAG, "resolutions: " + encode_resolutions.length);
                                Log.d(TAG, "fps: " + fps.length);
                                Log.d(TAG, "bitrates: " + bitrates.length);
                                Log.d(TAG, "i frame intervals: " + i_intervals.length);
                            } else {
                                Log.d(TAG, vc.size() + " nbr of test in " + description);
                                for (TestParams vc_: vcs) {
                                    Log.d(TAG, vc_.getSettings());
                                }
                                Log.d(TAG, "Add " + vc.size() + " number of test (" + description + ")");
                                vcs.addAll(vc);
                            }
                        }

                    }
                } else {
                    Log.d(TAG, "Error should be a jsons array or json object at the top level");
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            Log.e(TAG, e.getLocalizedMessage());
        } finally {
        }
        return vcs;
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
