import base64
import os
from typing import Type, Optional, Dict

from pydantic import BaseModel, Field

from copilot.core import utils
from copilot.core.threadcontext import ThreadContext
from copilot.core.tool_wrapper import ToolWrapper
from copilot.core.utils import copilot_debug


class AttachFileInput(BaseModel):
    filepath: str = Field(description="The path of the file to upload")
    ad_tab_id: str = Field(description="A string of 32 chars which is the ID of the Tab")
    record_id: str = Field(description="A string of 32 chars which is the ID of the record")


class AttachFileTool(ToolWrapper):
    """A tool to attach a file by uploading it to an API.

    Attributes:
        name (str): The name of the tool.
        description (str): A brief description of the tool.
    """

    name = "AttachFileTool"
    description = "Uploads a file to an API after checking its existence and accessibility."
    args_schema: Type[BaseModel] = AttachFileInput
    return_direct: bool = False

    def run(self, input_params: Dict, *args, **kwargs) -> str:
        filepath = input_params.get('filepath')
        ad_tab_id = input_params.get('ad_tab_id')
        record_id = input_params.get('record_id')

        # Check if the file exists and is accessible
        if not os.path.isfile(filepath) or not os.access(filepath, os.R_OK):
            return {"error": "File does not exist or is not accessible"}

        # Read the file and encode it in base64
        with open(filepath, "rb") as file:
            file_content = file.read()
            file_base64 = base64.b64encode(file_content).decode('utf-8')
        file_name = os.path.basename(filepath)
        extra_info = ThreadContext.get_data('extra_info')
        if extra_info is None or extra_info.get('auth') is None or extra_info.get('auth').get('ETENDO_TOKEN') is None:
            return {"error": "No access token provided, to work with Etendo, an access token is required."
                             "Make sure that the Webservices are enabled to the user role and the WS are configured for"
                             " the Entity."
                    }
        access_token = extra_info.get('auth').get('ETENDO_TOKEN')
        etendo_host = utils.read_optional_env_var("ETENDO_HOST", "http://host.docker.internal:8080/etendo")
        copilot_debug(f"ETENDO_HOST: {etendo_host}")
        return attach_file(etendo_host, access_token, ad_tab_id, record_id, file_name, file_base64)


def attach_file(url, access_token, ad_tab_id, record_id, file_name, file_base64):
    webhook_name = "AttachFile"
    body_params = {
        "ADTabId": ad_tab_id,
        "RecordId": record_id,
        "FileName": file_name,
        "FileContent": file_base64
    }
    post_result = call_webhook(access_token, body_params, url, webhook_name)
    return post_result


def call_webhook(access_token, body_params, url, webhook_name):
    import requests
    headers = _get_headers(access_token)
    endpoint = "/webhooks/?name=" + webhook_name
    import json
    json_data = json.dumps(body_params)
    full_url = (url + endpoint)
    copilot_debug(f"Calling Webhook(POST): {full_url}")
    post_result = requests.post(url=full_url, data=json_data, headers=headers)
    if post_result.ok:
        return json.loads(post_result.text)
    else:
        copilot_debug(post_result.text)
        return {"error": post_result.text}


def _get_headers(access_token: Optional[str]) -> Dict:
    """
    This method generates headers for an HTTP request.

    Parameters:
    access_token (str, optional): The access token to be included in the headers. If provided, an 'Authorization' field
     is added to the headers with the value 'Bearer {access_token}'.

    Returns:
    dict: A dictionary representing the headers. If an access token is provided, the dictionary includes an
     'Authorization' field.
    """
    headers = {}

    if access_token:
        headers["Authorization"] = f"Bearer {access_token}"
    return headers
