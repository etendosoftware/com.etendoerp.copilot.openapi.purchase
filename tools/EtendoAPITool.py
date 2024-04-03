import os
import threading
from typing import Dict, Final, Type, Optional

from langchain_community.agent_toolkits.openapi.spec import reduce_openapi_spec
from langchain_community.chat_models import ChatOpenAI
from langchain_community.utilities import RequestsWrapper
from langchain_core.tools import Tool
from pydantic import BaseModel, Field

from copilot.core import utils
from copilot.core.tool_wrapper import ToolWrapper
from copilot.core.utils import copilot_debug


class EtendoAPIToolInput(BaseModel):
    # Optional parameter endpoint, it indicates of what endpoint of the API we want to get the information
    endpoint: Optional[str] = Field(None,
                                    description="The endpoint of the API we want to get the information. If not provided, returns the general information of the API, listing all the endpoints. With description of each endpoint,but without the parameters or responses. "
                                    "It needs to include the Method and the Path of the endpoint. For example, if we want to get the information of the endpoint GET of the path /example, we need to provide the parameter endpoint with the value 'GET /example'. If the endpoint is provided, returns the information of that endpoint, with the parameters and responses."
                                    )


def _get_headers(access_token: Optional[str]) -> Dict:
    headers = {}

    if access_token:
        copilot_debug("token: " + access_token)
        headers["Authorization"] = f"Bearer {access_token}"

    return headers


def read_raw_api_spec(api_spec_file):
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
    # download url
    import requests
    response = requests.get(api_spec_file)
    return response.text


def check_and_set_server_url(raw_api_spec, server_url):
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

        from copilot.core.threadcontext import ThreadContext
        # read data of thread
        copilot_debug("Thread " + str(threading.get_ident()) + " TOOL:el que almacena el contexto es: " +
                      str(ThreadContext.identifier_data()))
        extra_info = ThreadContext.get_data('extra_info')
        copilot_debug("Extra info: " + str(extra_info))
        try:
            openai_model_for_agent: Final[str] = utils.read_optional_env_var("OPENAI_MODEL_FOR_OPENAPI",
                                                                             "gpt-4-turbo-preview")
            copilot_debug("OpenAPIEtendoTool: model selected ->" + str(openai_model_for_agent))
            etendo_host = "http://host.docker.internal:8080/etendo"

            endpoint = input_params.get('endpoint')

            # in local
            #api_spec_file = (
            #   '/Users/futit/Workspace/etendo_core/modules/com.etendoerp.copilot.openapi.purchase/web/com.etendoerp.copilot.openapi.purchase/doc/openapi3_1.json')
            # in docker
            api_spec_file = ('/modules/com.etendoerp.copilot.openapi.purchase/web/com.etendoerp.copilot.openapi.purchase/doc/openapi3_1.json')

            # for real
            #api_spec_file = (etendo_host + '/web/com.etendoerp.copilot.openapi.purchase/doc/openapi3_1.json')
            copilot_debug("The api spec file is: " + api_spec_file)
            server_url = etendo_host  # + '/sws/com.etendoerp.copilot.openapi.purchase.copilotws'

            access_token = extra_info.get('auth').get('ETENDO_TOKEN')
            # loads the language model we are going to use to control the agent

            # load the openapi specification, can be a local file or a remote url
            # check api_spec_file is a file or a url
            headers = _get_headers(access_token)

            raw_api_spec = read_raw_api_spec(api_spec_file)
            requests_wrapper = RequestsWrapper(headers=headers)

            check_and_set_server_url(raw_api_spec, server_url)
            reduced_openapi_spec = reduce_openapi_spec(raw_api_spec, dereference=False)
            agent_ex_arg = {
                "handle_parsing_errors": True
            }

            # import json
            # json.dumps(reduced_openapi_spec, indent=4, sort_keys=True)
            if (endpoint is not None):
                for endp in reduced_openapi_spec.endpoints:
                    if endp[0] == endpoint:
                        path = endp[0]
                        info = endp[2]
                        reqBody = info.get("requestBody")
                        responses = info.get('responses')
                        endpoint_data = {
                            "path": path,
                            "description": info.get('description'),
                            "requestBody": reqBody,
                            "responses": responses
                        }
                        return {'message': endpoint_data}
                response = {'error': "Endpoint not found"}

                return response

            servers = reduced_openapi_spec.servers
            url = ''
            if servers is not None and len(servers) > 0:
                url = "http://host.docker.internal:8080/etendo"
                #servers[0].get("url")

            endpoints_general = []
            for endp in reduced_openapi_spec.endpoints:
                endp_ = {
                    "path": endp[0]
                }
                endp_["description"]= endp[1]
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
