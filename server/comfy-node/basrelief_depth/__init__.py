from .nodes import MoGeVideoStream, MoGeGeometryNPZ, SharpStill

NODE_CLASS_MAPPINGS = {
    "MoGeVideoStream": MoGeVideoStream,
    "MoGeGeometryNPZ": MoGeGeometryNPZ,
    "SharpStill": SharpStill,
}

NODE_DISPLAY_NAME_MAPPINGS = {
    "MoGeVideoStream": "MoGe Video Stream (Basrelief)",
    "SharpStill": "SHARP Still -> Splat (Basrelief)",
}

__all__ = ["NODE_CLASS_MAPPINGS", "NODE_DISPLAY_NAME_MAPPINGS"]
