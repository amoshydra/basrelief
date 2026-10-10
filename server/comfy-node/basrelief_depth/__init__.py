from .nodes import MoGeVideoStream, MoGeGeometryNPZ

NODE_CLASS_MAPPINGS = {
    "MoGeVideoStream": MoGeVideoStream,
    "MoGeGeometryNPZ": MoGeGeometryNPZ,
}

NODE_DISPLAY_NAME_MAPPINGS = {
    "MoGeVideoStream": "MoGe Video Stream (Basrelief)",
}

__all__ = ["NODE_CLASS_MAPPINGS", "NODE_DISPLAY_NAME_MAPPINGS"]
