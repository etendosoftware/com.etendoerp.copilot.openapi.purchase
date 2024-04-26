import os
import threading
from typing import Dict, Final, Type, Optional

from langchain_community.agent_toolkits.openapi.spec import reduce_openapi_spec
from langchain_community.chat_models import ChatOpenAI
from langchain_community.utilities import RequestsWrapper
from langchain_core.tools import Tool
from pydantic import BaseModel, Field

from copilot.core import utils
from copilot.core.threadcontext import ThreadContext
from copilot.core.tool_wrapper import ToolWrapper
from copilot.core.utils import copilot_debug


class EtendoAPIToolInput(BaseModel):
    tag: Optional[str] = Field(None,
                               description="The tag of the API endpoints we want to get the information. If provided, returns the information of the endpoints with that tag( filter by tag). If not provided, no filter is applied. If the endpoint is provided, the tag parameter is ignored."
                               )
    endpoint: Optional[str] = Field(None,
                                    description="The endpoint of the API we want to get the information. If not provided, returns the general information of the API, listing all the endpoints. With description of each endpoint,but without the parameters or responses. "
                                                "It needs to include the Method and the Path of the endpoint. For example, if we want to get the information of the endpoint GET of the path /example, we need to provide the parameter endpoint with the value 'GET /example'. If the endpoint is provided, returns the information of that endpoint, with the parameters and responses."
                                    )


def _get_headers(access_token: Optional[str]) -> Dict:
    """
    This method generates headers for an HTTP request.

    Parameters:
    access_token (str, optional): The access token to be included in the headers. If provided, an 'Authorization' field is added to the headers with the value 'Bearer {access_token}'.

    Returns:
    dict: A dictionary representing the headers. If an access token is provided, the dictionary includes an 'Authorization' field.
    """
    headers = {}

    if access_token:
        copilot_debug("token: " + access_token)
        headers["Authorization"] = f"Bearer {access_token}"

    return headers


def read_raw_api_spec(api_spec_file):
    """
    This method reads the raw API specification from a given file or URL.

    Parameters:
    api_spec_file (str): The path to the API specification file or URL.

    Returns:
    dict: A dictionary representing the raw API specification. The dictionary is loaded from a JSON or YAML file or URL.

    Raises:
    FileNotFoundError: If the file does not exist.
    """
    import yaml
    isfile = os.path.isfile(api_spec_file)
    if isfile:
        copilot_debug("detected as local file!")
        with open(api_spec_file, 'r') as file:
            file_content = file.read()

    else:
        # download the file
        file_content = download_file(api_spec_file)
    # check if the file is a yaml file or a json file
    is_json = file_content.startswith('{') or file_content.startswith('[')
    if is_json:
        copilot_debug("detected as json file!")
        import json
        raw_api_spec = json.loads(file_content)
    else:
        copilot_debug("detected as yaml file!")
        raw_api_spec = yaml.load(file_content, Loader=yaml.Loader)

    copilot_debug("info readed")
    return raw_api_spec


def download_file(api_spec_file):
    """
    This method downloads the content of a file from a given URL.

    Parameters:
    api_spec_file (str): The URL of the file to download.

    Returns:
    str: The content of the downloaded file as a string.
    """
    # download url
    import requests
    response = requests.get(api_spec_file)
    return response.text


def check_and_set_server_url(raw_api_spec, server_url):
    """
    This method checks and sets the server URL in the raw API specification.

    Parameters:
    raw_api_spec (dict): The raw API specification as a dictionary. It should have a 'servers' key if the server_url parameter is not provided.
    server_url (str): The server URL to be set in the raw API specification. If provided, a 'servers' key is added to the raw API specification with the value [{"url": server_url}].

    Raises:
    ValueError: If the server_url is not provided, and the 'servers' key is not present in the raw API specification, and the 'host' and 'basePath' keys are not present in the raw API specification. In this case, the server_url needs to be defined or the 'servers' key needs to be present in the raw API specification.
    """
    if server_url is not None:
        raw_api_spec['servers'] = [{"url": server_url}]
        return

    # if the parameter server_url is not defined, check if the servers are defined in the openapi spec
    if 'servers' in raw_api_spec:
        return

    # is not in the spec, but maybe the host and basePath are defined, because is openapi 2.0
    if 'host' in raw_api_spec and 'basePath' in raw_api_spec:
        raw_api_spec['servers'] = [{"url": f"{raw_api_spec['host']}{raw_api_spec['basePath']}"}]
        return

    # if the server_url is not defined, and the servers are not defined, and the host and basePath are not defined
    # raise an error
    raise ValueError("The server_url is not defined, and the servers are not defined in the openapi spec, "
                     "and the host and basePath are not defined in the openapi spec. You need to define the server_url "
                     "or the servers in the openapi spec")


def search_paths_with_tag(raw_api_spec, tag):
    paths_with_tag = []
    paths = raw_api_spec["paths"]
    for path_raw, data in paths.items():
        for method, data_method in data.items():
            if tag in data_method.get('tags'):
                paths_with_tag.append(method.upper() + " " + path_raw)
    return paths_with_tag


def verify_path(path):
    # Check if the path is absolute
    copilot_debug(f"Verifying path: {path}")
    if os.path.isabs(path):
        base_path = '/'
    else:
        base_path = os.getcwd()

    # Split the path into parts
    parts = path.strip('/').split('/')

    # Iterate over each part of the path
    for part in parts:
        base_path = os.path.join(base_path, part)

        # Check if the current part of the path exists
        if not os.path.exists(base_path):
            copilot_debug(f"Path does not exist: {base_path}")
            return False

    copilot_debug(f"All components exist for the path: {path}")
    return True


def has_token(extra_info):
    """
    This method checks if a token is present in the provided extra information.

    Parameters:
    extra_info (dict): A dictionary containing additional information. It should have a key 'auth' which is a dictionary that contains the key 'ETENDO_TOKEN'.

    Returns:
    bool: True if the token is not present or the 'auth' key is not present in the extra_info dictionary, False otherwise.
    """
    return extra_info is None or extra_info.get('auth') is None or extra_info.get('auth').get(
        'ETENDO_TOKEN') is None


def include_endpoint(endp, paths_with_tag, tag):
    """
    This method checks if a given endpoint should be included in the final list of endpoints.

    Parameters:
    endp (tuple): A tuple representing an endpoint. The first element of the tuple is the path of the endpoint.
    paths_with_tag (list): A list of paths that have the specified tag.
    tag (str): The tag to filter the endpoints. If None, all endpoints are included.

    Returns:
    bool: True if the endpoint should be included, False otherwise. An endpoint is included if no tag is provided or if the endpoint's path is in the list of paths with the specified tag.
    """
    return tag is None or endp[0] in paths_with_tag


def read_extra_info():
    """
    This method reads the extra information from the current thread context.

    Returns:
    dict: A dictionary containing the extra information from the current thread context. The dictionary is retrieved using the 'extra_info' key.

    Side Effects:
    This method logs the thread identifier and the extra information retrieved.
    """
    # read data of thread
    copilot_debug("Thread " + str(threading.get_ident()) + " TOOL:el que almacena el contexto es: " +
                  str(ThreadContext.identifier_data()))
    extra_info = ThreadContext.get_data('extra_info')
    copilot_debug("Extra info: " + str(extra_info))
    return extra_info


class EtendoAPITool(ToolWrapper):
    name = "EtendoAPITool"
    description = (''' This Tool, based on the OpenAPI specification, allows you to get information about the API,
    such as the url of the server, the endpoints, the parameters of each endpoint, the responses of each endpoint, etc.
    It has one optional parameter, endpoint, which indicates of what endpoint of the API we want to get the information. It needs
    to include the Method and the Path of the endpoint. For example, if we want to get the information of the endpoint
    GET of the path /example, we need to provide the parameter endpoint with the value "GET /example".
    If not provided, returns the general information of the API, listing all the endpoints, with description of each endpoint,
    but without the parameters or responses. If the endpoint is provided, returns the information of that endpoint, with the
    parameters and responses.
    ''')
    args_schema: Type[BaseModel] = EtendoAPIToolInput

    def run(self, input_params, *args, **kwargs):
        """
        This method runs the Etendo API tool.

        Parameters:
        input_params (dict): A dictionary containing the input parameters for the tool. It should have the keys 'endpoint' and 'tag'.
        *args: Variable length argument list.
        **kwargs: Arbitrary keyword arguments.

        Returns:
        dict: A dictionary containing the results of the tool. If an error occurs, the dictionary contains an 'error' key.

        Raises:
        Exception: If an error occurs while running the tool.
        """
        extra_info = read_extra_info()
        try:
            openai_model_for_agent: Final[str] = utils.read_optional_env_var("OPENAI_MODEL_FOR_OPENAPI",
                                                                             "gpt-4-turbo-preview")
            copilot_debug("OpenAPIEtendoTool: model selected ->" + str(openai_model_for_agent))
            etendo_host = utils.read_optional_env_var("ETENDO_HOST", "http://host.docker.internal:8080/etendo")

            endpoint = input_params.get('endpoint')
            tag = input_params.get('tag')
            # if the endpoint has query parameters, we need to remove them
            if endpoint is not None:
                endpoint = endpoint.split('?')[0]

            # in local
            api_spec_file = os.getenv('COPILOT_PURCHASE_API_SPEC_FILE',
                                      '/modules/com.etendoerp.copilot.openapi.purchase/web/com.etendoerp.copilot.openapi.purchase/doc/openapi3_1.json')

            verify_path(api_spec_file)
            # for real
            # api_spec_file = (etendo_host + '/web/com.etendoerp.copilot.openapi.purchase/doc/openapi3_1.json')
            copilot_debug("The api spec file is: " + api_spec_file)
            server_url = etendo_host  # + '/sws/com.etendoerp.copilot.openapi.purchase.copilotws'

            if has_token(extra_info):
                copilot_debug("No token found")
                return {
                    'error': "No token found in context. Check the Secure Web Services configuration "
                             "in 'Client' window as 'System administrator'"}
            access_token = extra_info.get('auth').get('ETENDO_TOKEN')
            # loads the language model we are going to use to control the agent

            # load the openapi specification, can be a local file or a remote url
            # check api_spec_file is a file or a url
            headers = _get_headers(access_token)
            copilot_debug("Headers: " + str(headers))

            raw_api_spec = read_raw_api_spec(api_spec_file)
            copilot_debug("Requests wrapper created")
            check_and_set_server_url(raw_api_spec, server_url)
            copilot_debug("Server url checked")
            reduced_openapi_spec = reduce_openapi_spec(raw_api_spec, dereference=False)
            copilot_debug("OpenAPI spec reduced")

            if endpoint is not None:
                copilot_debug("Endpoint provided")
                for endp in reduced_openapi_spec.endpoints:
                    if endp[0] == endpoint:
                        path = endp[0]
                        info = endp[2]
                        req_body = info.get("requestBody")
                        responses = info.get('responses')
                        endpoint_data = {
                            "path": path,
                            "description": info.get('description'),
                            "requestBody": req_body,
                            "responses": responses
                        }
                        return {'message': endpoint_data}
                response = {'error': "Endpoint not found"}

                return response

            servers = reduced_openapi_spec.servers
            url = ''
            if servers is not None and len(servers) > 0:
                copilot_debug("Servers found")
                url = etendo_host
                copilot_debug("Server url: " + url)

            endpoints_general = []
            paths_with_tag = []
            if tag is not None:  # read the paths with the tag
                paths_with_tag = search_paths_with_tag(raw_api_spec, tag)
                copilot_debug("Paths with tag: " + str(paths_with_tag))
            for endp in reduced_openapi_spec.endpoints:
                if include_endpoint(endp, paths_with_tag, tag):
                    endp_ = {
                        "path": endp[0],
                        "description": endp[1]
                    }
                    endpoints_general.append(endp_)
            response: Dict = {
                "token": access_token,
                "url": url,
                "description": reduced_openapi_spec.description,
                "endpoints": endpoints_general

            }
            response = {'message': response}
            return response
        except Exception as e:
            response = {'error': str(e)}
            return response
