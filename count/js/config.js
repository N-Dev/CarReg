// TrafficSight versions and model files. The AI runtime version and the build id come from PlateSight's
// ../js/config.js (one source of truth for both apps); the publish workflow stamps the build id there.
self.TC_CONFIG = {
  release: '1.0',
  models: {
    // YOLOX (Megvii, Apache-2.0), trained on COCO: people, animals and vehicles among its 80 classes.
    tiny: { file: 'models/vehicles-tiny.onnx', bytes: 20219662, rev: '427cc366d34e', size: 416, label: 'Standard' },
    nano: { file: 'models/vehicles-nano.onnx', bytes: 3659407, rev: 'c789161ed43c', size: 416, label: 'Light' },
  },
};
