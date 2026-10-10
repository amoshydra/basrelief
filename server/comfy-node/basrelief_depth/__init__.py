from .nodes import MoGeVideoStream

NODE_CLASS_MAPPINGS = {
    "MoGeVideoStream": MoGeVideoStream,
}

NODE_DISPLAY_NAME_MAPPINGS = {
    "MoGeVideoStream": "MoGe Video Stream (Basrelief)",
}

__all__ = ["NODE_CLASS_MAPPINGS", "NODE_DISPLAY_NAME_MAPPINGS"]
