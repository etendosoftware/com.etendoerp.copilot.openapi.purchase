import os
import threading
from typing import Dict, Final, Type, Optional

from langchain_community.agent_toolkits.openapi import planner
from langchain_community.agent_toolkits.openapi.spec import reduce_openapi_spec
from langchain_community.chat_models import ChatOpenAI
from langchain_community.utilities import RequestsWrapper
from pydantic import BaseModel, Field

from copilot.core import utils
from copilot.core.tool_wrapper import ToolWrapper
from copilot.core.utils import copilot_debug


class OpenAPIEtendoToolInput(BaseModel):
    question_prompt: str = Field(description="The question/request prompt to be asked to the OpenAPI specification")


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
        with open(api_spec_file, 'r') as file:
            file_content = file.read()

    else:
        # download the file
        file_content = download_file(api_spec_file)
    # check if the file is a yaml file or a json file
    is_json = file_content.startswith('{') or file_content.startswith('[')
    if is_json:
        import json
        raw_api_spec = json.loads(file_content)
    else:
        raw_api_spec = yaml.load(file_content, Loader=yaml.Loader)

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


class OpenAPIEtendoTool(ToolWrapper):
    name = "OpenAPIEtendoTool"
    description = (''' This Tool, based on the OpenAPI specification, allows you to interact with the ETENDO API.
    ''')
    args_schema: Type[BaseModel] = OpenAPIEtendoToolInput

    def run(self, input_params, *args, **kwargs):
        from copilot.core.threadcontext import ThreadContext
        # read data of thread
        print("Thread ", threading.get_ident(), " TOOL:el que almacena el contexto es: ",
              ThreadContext.identifier_data())
        extra_info = ThreadContext.get_data('extra_info')
        copilot_debug("Extra info: " + str(extra_info))
        try:
            openai_model_for_agent: Final[str] = utils.read_optional_env_var("OPENAI_MODEL_FOR_OPENAPI",
                                                                             "gpt-4-turbo-preview")

            api_spec_file = ('http://localhost:8080/etendo/web/com.etendoerp.copilot.openapi.purchase/doc/'
                             'openapi3_1_sinlogin.json')
            server_url = 'http://localhost:8080/etendo'

            question_prompt = input_params.get('question_prompt')
            access_token = extra_info.get('auth').get('ETENDO_TOKEN')
            # loads the language model we are going to use to control the agent
            llm = ChatOpenAI(temperature=0, model_name=openai_model_for_agent)

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
            openapi_agent_executor = planner.create_openapi_agent(
                reduced_openapi_spec, requests_wrapper, llm, agent_executor_kwargs=agent_ex_arg
            )

            response = openapi_agent_executor.invoke({"input": question_prompt})

            response = {'message': response["output"]}
            return response
        except Exception as e:
            response = {'error': str(e)}
            return response
